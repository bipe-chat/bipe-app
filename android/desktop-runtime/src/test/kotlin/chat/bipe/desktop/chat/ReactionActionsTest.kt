// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.*
import chat.bipe.shared.generated.BipeReactions
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.Reaction
import org.matrix.rustcomponents.sdk.ReactionSenderData

class ReactionActionsTest {
    @Test fun sdkSnapshotsPreserveFullKeysOwnSelectionAndNewestSenderOrder() {
        val key = "👨‍👩‍👧‍👦".repeat(18)
        val snapshot = reactionSnapshots(listOf(Reaction(key, listOf(
            ReactionSenderData("@own:example.test", 1uL), ReactionSenderData("@other:example.test", 3uL))),
            Reaction("empty", emptyList())), "@own:example.test").single()
        assertEquals(key, snapshot.key)
        assertEquals(2, snapshot.count)
        assertTrue(snapshot.includesOwn)
        assertEquals(listOf(3L, 1L), snapshot.senders.map { it.reactedAt })
        assertEquals("👨‍👩‍👧‍👦".repeat(16) + "…", BipeReactions.keyText(key))
    }

    @Test fun repeatClicksDoNotDoubleToggleAndTheOriginalKeyReachesTheSdk() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val key = "👍🏽".repeat(20)
            val group = AccountTaskGroup.create(owner)
            val actions = ReactionActions(group, { message() }) { eventId, sentKey ->
                assertEquals("\$synthetic", eventId); assertEquals(key, sentKey)
                calls.incrementAndGet(); entered.complete(Unit); release.await()
            }
            actions.toggle("row", key); entered.await()
            repeat(10) { actions.toggle("row", key) }
            release.complete(Unit)
            actions.phase.first { it == ReactionActionPhase.Idle }
            assertEquals(1, calls.get())
        }
    }

    @Test fun failedToggleCanRetryWhileUnsentAndUnavailableRowsCannotReact() = runBlocking {
        withMediaOwner { owner ->
            val group = AccountTaskGroup.create(owner)
            val calls = AtomicInteger()
            val actions = ReactionActions(group, { if (it == "row") message() else message().copy(eventId = null) }) { _, _ ->
                if (calls.incrementAndGet() == 1) error("Synthetic offline reaction")
            }
            actions.toggle("unsent", "👍")
            actions.toggle("row", "")
            assertEquals(0, calls.get())
            actions.toggle("row", "👍")
            actions.phase.first { it is ReactionActionPhase.Failed }
            actions.toggle("row", "👍")
            actions.phase.first { it == ReactionActionPhase.Idle }
            assertEquals(2, calls.get())
            group.close()
            actions.toggle("row", "👍")
            assertEquals(2, calls.get())
        }
    }

    @Test fun retiringTheRoomCannotPublishACompletedToggleIntoAnotherRoom() = runBlocking {
        withMediaOwner { owner ->
            val group = AccountTaskGroup.create(owner)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val actions = ReactionActions(group, { message() }) { _, _ -> withContext(NonCancellable) {
                entered.complete(Unit); release.await()
            } }
            actions.toggle("row", "👍"); entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { group.close() }
            release.complete(Unit); closing.await()
            assertTrue(actions.phase.value is ReactionActionPhase.Sending)
            assertTrue(owner.isCurrent())
        }
    }

    private fun message() = MessageRow("row", "Person", false, "Synthetic", MessageRow.Kind.TEXT, 1,
        MessageRow.Delivery.SENT, eventId = "\$synthetic")
}
