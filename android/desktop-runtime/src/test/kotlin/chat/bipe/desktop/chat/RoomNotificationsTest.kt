// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class RoomNotificationsTest {
    @Test fun dmLegacyModeIsOnlyShownWhileCurrentAndCanNeverBeChosen() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(true, true, NotifyMode.LEGACY, NotifyMode.ALL))
            val model = RoomNotifications.create(owner, "original", backend)
            assertEquals(listOf(NotifyMode.ALL, NotifyMode.MUTE, NotifyMode.LEGACY), model.ready().snapshot.options)
            model.choose(NotifyMode.LEGACY); assertTrue(backend.writes.isEmpty())
            model.choose(NotifyMode.ALL)
            assertEquals(listOf(NotifyMode.ALL, NotifyMode.MUTE), model.settled().snapshot.options)
            assertEquals(listOf("restore"), backend.writes)
        }
    }
    @Test fun encryptedGroupQuietUsesThePlainDefaultAndExplicitAllKeepsARoomRule() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(false, true, NotifyMode.QUIET, NotifyMode.QUIET))
            val model = RoomNotifications.create(owner, "group", backend)
            assertEquals(listOf(NotifyMode.QUIET, NotifyMode.ALL, NotifyMode.MUTE), model.ready().snapshot.options)
            model.choose(NotifyMode.ALL); model.settled()
            model.choose(NotifyMode.QUIET); model.settled()
            assertEquals(listOf("set:ALL", "restore"), backend.writes)
        }
    }
    @Test fun encryptedGroupWithAllAsDefaultCannotChooseAQuietRuleThatWouldStopPushes() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(false, true, NotifyMode.ALL, NotifyMode.ALL))
            val model = RoomNotifications.create(owner, "group", backend)
            assertEquals(listOf(NotifyMode.ALL, NotifyMode.MUTE), model.ready().snapshot.options)
            model.choose(NotifyMode.QUIET)
            assertTrue(backend.writes.isEmpty())
        }
    }
    @Test fun oldMentionsOnlyRuleInAnEncryptedGroupIsRepairedBeforeShowingTheChoice() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(false, true, NotifyMode.QUIET, NotifyMode.QUIET, repairLegacyGroup = true))
            val model = RoomNotifications.create(owner, "group", backend)
            assertFalse(model.ready().snapshot.repairLegacyGroup)
            assertEquals(listOf("restore"), backend.writes)
        }
    }
    @Test fun partialUnmuteFailureKeepsTheOldChoiceAndRetryUsesFreshStateWithoutUnmutingTwice() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(false, true, NotifyMode.MUTE, NotifyMode.QUIET)).apply { failSet = true }
            val model = RoomNotifications.create(owner, "group", backend)
            model.ready(); model.choose(NotifyMode.ALL)
            val failed = model.settled()
            assertEquals(NotifyMode.MUTE, failed.snapshot.current); assertTrue(failed.save is NotifySave.Failed)
            backend.failSet = false; model.retry()
            assertEquals(NotifyMode.ALL, model.settled().snapshot.current)
            assertEquals(listOf("unmute", "set:ALL", "set:ALL"), backend.writes)
        }
    }
    @Test fun changesReadTheCurrentDefaultAndAnUnavailableChoiceFailsWithoutWriting() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend(NotifySnapshot(false, true, NotifyMode.ALL, NotifyMode.QUIET))
            val model = RoomNotifications.create(owner, "group", backend)
            model.ready(); backend.snapshot = backend.snapshot.copy(default = NotifyMode.ALL)
            model.choose(NotifyMode.QUIET)
            assertTrue(model.settled().save is NotifySave.Failed)
            assertTrue(backend.writes.isEmpty())
        }
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
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertTrue((model.phase.value as NotifyPhase.Ready).save is NotifySave.Sending)
            assertEquals(listOf("set:MUTE"), backend.writes)
            withMediaOwner { other ->
                val independent = RoomNotifications.create(other, "other", Backend(NotifySnapshot(true, true, NotifyMode.ALL, NotifyMode.ALL)))
                assertEquals(NotifyMode.ALL, independent.ready().snapshot.current)
            }
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
