// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomNotificationsTest {
    /** Runs [steps] on a model over a fake backend that starts at [snapshot]; returns the facts the steps report. */
    private fun scenario(snapshot: NotifySnapshot, room: String = "group",
                         steps: suspend (RoomNotifications, Backend) -> Map<String, Any?>): Map<String, Any?> = runBlocking {
        var facts: Map<String, Any?> = emptyMap()
        withMediaOwner { owner ->
            val backend = Backend(snapshot)
            facts = steps(RoomNotifications.create(owner, room, backend), backend)
        }
        facts
    }

    @Test fun dmLegacyModeIsOnlyShownWhileCurrentAndCanNeverBeChosen() {
        val facts = scenario(NotifySnapshot(true, true, NotifyMode.LEGACY, NotifyMode.ALL), "original") { model, backend ->
            val shown = model.ready().snapshot.options
            model.choose(NotifyMode.LEGACY); val afterLegacy = backend.writes.toList()
            model.choose(NotifyMode.ALL)
            mapOf("shown" to shown, "legacy writes" to afterLegacy, "after all" to model.settled().snapshot.options, "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("shown" to listOf(NotifyMode.ALL, NotifyMode.MUTE, NotifyMode.LEGACY), "legacy writes" to emptyList<String>(),
            "after all" to listOf(NotifyMode.ALL, NotifyMode.MUTE), "writes" to listOf("restore")), facts)
    }

    @Test fun encryptedGroupQuietUsesThePlainDefaultAndExplicitAllKeepsARoomRule() {
        val facts = scenario(NotifySnapshot(false, true, NotifyMode.QUIET, NotifyMode.QUIET)) { model, backend ->
            val shown = model.ready().snapshot.options
            model.choose(NotifyMode.ALL); model.settled()
            model.choose(NotifyMode.QUIET); model.settled()
            mapOf("shown" to shown, "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("shown" to listOf(NotifyMode.QUIET, NotifyMode.ALL, NotifyMode.MUTE), "writes" to listOf("set:ALL", "restore")), facts)
    }

    @Test fun encryptedGroupWithAllAsDefaultCannotChooseAQuietRuleThatWouldStopPushes() {
        val facts = scenario(NotifySnapshot(false, true, NotifyMode.ALL, NotifyMode.ALL)) { model, backend ->
            val shown = model.ready().snapshot.options
            model.choose(NotifyMode.QUIET)
            mapOf("shown" to shown, "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("shown" to listOf(NotifyMode.ALL, NotifyMode.MUTE), "writes" to emptyList<String>()), facts)
    }

    @Test fun oldMentionsOnlyRuleInAnEncryptedGroupIsRepairedBeforeShowingTheChoice() {
        val facts = scenario(NotifySnapshot(false, true, NotifyMode.QUIET, NotifyMode.QUIET, repairLegacyGroup = true)) { model, backend ->
            mapOf("still needs repair" to model.ready().snapshot.repairLegacyGroup, "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("still needs repair" to false, "writes" to listOf("restore")), facts)
    }

    @Test fun partialUnmuteFailureKeepsTheOldChoiceAndRetryUsesFreshStateWithoutUnmutingTwice() {
        val facts = scenario(NotifySnapshot(false, true, NotifyMode.MUTE, NotifyMode.QUIET)) { model, backend ->
            backend.failSet = true
            model.ready(); model.choose(NotifyMode.ALL)
            val failed = model.settled()
            backend.failSet = false; model.retry()
            mapOf("after failure" to (failed.snapshot.current to (failed.save is NotifySave.Failed)),
                "after retry" to model.settled().snapshot.current, "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("after failure" to (NotifyMode.MUTE to true), "after retry" to NotifyMode.ALL,
            "writes" to listOf("unmute", "set:ALL", "set:ALL")), facts)
    }

    @Test fun changesReadTheCurrentDefaultAndAnUnavailableChoiceFailsWithoutWriting() {
        val facts = scenario(NotifySnapshot(false, true, NotifyMode.ALL, NotifyMode.QUIET)) { model, backend ->
            model.ready(); backend.snapshot = backend.snapshot.copy(default = NotifyMode.ALL)
            model.choose(NotifyMode.QUIET)
            mapOf("failed" to (model.settled().save is NotifySave.Failed), "writes" to backend.writes.toList())
        }
        assertEquals(mapOf("failed" to true, "writes" to emptyList<String>()), facts)
    }

    @Test fun inFlightChoicesCoalesceAndRetiredAccountsCannotPublishLateAcknowledgement() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val backend = Backend(NotifySnapshot(true, true, NotifyMode.ALL, NotifyMode.ALL)).apply {
                onSet = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            val model = RoomNotifications.create(owner, "original", backend)
            model.ready(); model.choose(NotifyMode.MUTE); entered.await(); model.choose(NotifyMode.ALL)
            val closing = owner.closeAsync(); release.complete(Unit)
            val closed = closing.await()
            val retired = listOf(closed, (model.phase.value as NotifyPhase.Ready).save is NotifySave.Sending, backend.writes.toList())
            var independent: NotifyMode? = null
            withMediaOwner { other ->
                independent = RoomNotifications.create(other, "other", Backend(NotifySnapshot(true, true, NotifyMode.ALL, NotifyMode.ALL)))
                    .ready().snapshot.current
            }
            assertEquals(listOf(AccountCloseResult.Closed, true, listOf("set:MUTE")) to NotifyMode.ALL, retired to independent)
        }
    }

    private suspend fun RoomNotifications.ready() = withTimeout(3_000) { phase.first { it is NotifyPhase.Ready } as NotifyPhase.Ready }
    private suspend fun RoomNotifications.settled() = withTimeout(3_000) { phase.first { it is NotifyPhase.Ready && it.save !is NotifySave.Sending } as NotifyPhase.Ready }
    private class Backend(var snapshot: NotifySnapshot) : NotifyBackend {
        val writes = mutableListOf<String>()
        var failSet = false
        var onSet: suspend () -> Unit = {}
        override suspend fun read() = snapshot
        override suspend fun restore() { writes += "restore"; snapshot = snapshot.copy(current = snapshot.default, repairLegacyGroup = false) }
        override suspend fun unmute(snapshot: NotifySnapshot) { writes += "unmute"; this.snapshot = snapshot.copy(current = snapshot.default) }
        override suspend fun set(mode: NotifyMode) { writes += "set:$mode"; check(!failSet); onSet(); snapshot = snapshot.copy(current = mode) }
    }
}
