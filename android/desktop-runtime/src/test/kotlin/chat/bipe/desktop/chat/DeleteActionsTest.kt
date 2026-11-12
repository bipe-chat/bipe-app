// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountTaskGroup
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DeleteActionsTest {
    @Test fun readingCandidatesDoesNotDeleteAndOnlyOwnSentRowsCanBeConfirmed() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend()
            var current = message()
            val model = DeleteActions(AccountTaskGroup.create(owner), { current }, backend)
            model.start(); model.ready()
            val intent = model.candidate("row")!!
            assertEquals("\$own", intent.eventId); assertEquals(0, backend.calls.get())
            assertNull(model.candidate("another-row"))
            current = current.copy(own = false)
            assertNull(model.candidate("row")); model.confirm(intent)
            current = message().copy(delivery = MessageRow.Delivery.FAILED)
            assertNull(model.candidate("row")); model.confirm(intent)
            current = message().copy(kind = MessageRow.Kind.DELETED)
            assertNull(model.candidate("row")); model.confirm(intent)
            assertEquals(0, backend.calls.get())
        }
    }

    @Test fun aStaleDialogCannotDeleteAReplacementTargetOrAnotherAccountsRow() = runBlocking {
        withMediaOwner { first -> withMediaOwner { second ->
            val backend = Backend()
            var current: MessageRow? = message()
            val model = DeleteActions(AccountTaskGroup.create(first), { current }, backend)
            model.start(); model.ready()
            val original = model.candidate("row")!!
            current = message().copy(eventId = "\$replacement")
            model.confirm(original)
            current = null; model.confirm(original)
            assertEquals(0, backend.calls.get())
            val other = DeleteActions(AccountTaskGroup.create(second), { message() }, backend)
            other.start(); other.ready(); other.confirm(original)
            assertEquals(0, backend.calls.get())
        } }
    }

    @Test fun duplicateConfirmationsDispatchOneRedactionAndFailuresCanRetryTheOriginalTarget() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val backend = Backend().apply { perform = { entered.complete(Unit); release.await(); error("Synthetic redaction failure") } }
            val model = DeleteActions(AccountTaskGroup.create(owner), { message() }, backend)
            model.start(); model.ready()
            val intent = model.candidate("row")!!
            model.confirm(intent); entered.await()
            repeat(10) { model.confirm(intent) }
            release.complete(Unit)
            model.phase.first { it is DeletePhase.Failed }
            assertEquals(1, backend.calls.get())
            backend.perform = { assertEquals("\$own", it) }
            model.confirm(intent); model.phase.first { it == DeletePhase.Idle }
            assertEquals(2, backend.calls.get())
        }
    }

    @Test fun revokedAndUnavailablePermissionsStayClosedUntilARefreshSucceeds() = runBlocking {
        withMediaOwner { owner ->
            val backend = Backend().apply { allowed = false }
            val model = DeleteActions(AccountTaskGroup.create(owner), { message() }, backend)
            model.start(); model.ready()
            assertNull(model.candidate("row"))
            backend.failRead = true; model.refresh()
            model.permission.first { it == DeletePermission.Unavailable }
            assertNull(model.candidate("row"))
            backend.failRead = false; backend.allowed = true; model.refresh(); model.ready()
            val intent = model.candidate("row")!!
            // The backend reads permission again after a confirmation, so revocation cannot use cached rights.
            backend.allowed = false
            model.confirm(intent); model.phase.first { it is DeletePhase.Failed }
            assertEquals(0, backend.calls.get())
        }
    }

    @Test fun aRetiredRoomCannotPublishALateRedactionOrDispatchAgain() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val backend = Backend().apply { perform = { withContext(NonCancellable) { entered.complete(Unit); release.await() } } }
            val group = AccountTaskGroup.create(owner)
            val model = DeleteActions(group, { message() }, backend)
            model.start(); model.ready()
            val intent = model.candidate("row")!!
            model.confirm(intent); entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { group.close() }
            release.complete(Unit); closing.await()
            assertTrue(model.phase.value is DeletePhase.Deleting)
            assertNull(model.candidate("row")); model.confirm(intent)
            assertEquals(1, backend.calls.get()); assertTrue(owner.isCurrent())
        }
    }

    private suspend fun DeleteActions.ready() = withTimeout(2_000) { permission.first { it is DeletePermission.Ready } }
    private class Backend : DeleteBackend {
        val calls = AtomicInteger()
        @Volatile var allowed = true
        @Volatile var failRead = false
        var perform: suspend (String) -> Unit = {}
        override suspend fun canDeleteOwn(): Boolean { check(!failRead); return allowed }
        override suspend fun deleteOwn(eventId: String) { check(canDeleteOwn()); calls.incrementAndGet(); perform(eventId) }
    }
    private fun message() = MessageRow("row", "You", true, "Synthetic own message", MessageRow.Kind.TEXT, 1,
        MessageRow.Delivery.SENT, eventId = "\$own")
}
