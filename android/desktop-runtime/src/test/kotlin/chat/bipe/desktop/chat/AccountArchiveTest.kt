// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountCloseResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class AccountArchiveTest {
    @Test fun aConfirmedArchiveRetainsHistoryUnreadAndDraftAndSuppressesTheOldFavouriteEcho() = runBlocking {
        withMediaOwner { owner ->
            val writes = mutableListOf<Pair<String, Boolean>>()
            val journal = Journal()
            val model = AccountArchive.create(owner, ArchiveBackend { room, value -> writes += room to value }, journal)
            val chat = row().copy(favourite = true, unread = 7, draft = "Retained draft")
            model.reconcile(listOf(chat)); model.set(chat, true)
            val state = model.await { it.markers[chat.id] == 100L && it.operations.isEmpty() }
            val displayed = state.effective(chat)
            assertTrue(displayed.archived); assertFalse(displayed.favourite)
            assertEquals(7L, displayed.unread); assertEquals(chat.draft, displayed.draft); assertEquals(chat.preview, displayed.preview)
            assertEquals(listOf(chat.id to true), writes)
            assertFalse(journal.value!!.contains("Retained draft")); assertFalse(journal.value!!.contains(chat.preview!!))
        }
    }

    @Test fun onlyStrictlyNewerIncomingMessagesRestoreIncludingBeforeTheTagSyncEcho() = runBlocking {
        withMediaOwner { owner ->
            val writes = mutableListOf<Boolean>()
            val model = AccountArchive.create(owner, ArchiveBackend { _, value -> writes += value }, Journal())
            val chat = row()
            model.set(chat, true); model.await { it.markers[chat.id] == 100L && it.operations.isEmpty() }
            model.reconcile(listOf(chat.copy(timestamp = 300, latestIncoming = null))) // Own send or state change.
            model.reconcile(listOf(chat.copy(latestIncoming = 99))) // Backfill.
            model.reconcile(listOf(chat.copy(latestIncoming = 100))) // Equal is not newer.
            assertEquals(listOf(true), writes)
            model.reconcile(listOf(chat.copy(timestamp = 301, latestIncoming = 101))) // SDK tag can still say false.
            val restored = model.await { chat.id !in it.markers && it.operations.isEmpty() }
            assertFalse(restored.effective(chat.copy(archived = true, favourite = true)).archived)
            assertFalse(restored.effective(chat.copy(favourite = true)).favourite)
            assertEquals(listOf(true, false), writes)
        }
    }

    @Test fun importedRemoteArchivesGetABaselineAndRemoteUnarchiveClearsIt() = runBlocking {
        withMediaOwner { owner ->
            val writes = mutableListOf<Boolean>()
            val model = AccountArchive.create(owner, ArchiveBackend { _, value -> writes += value }, Journal())
            model.reconcile(listOf(row().copy(archived = true, latestIncoming = 90)))
            assertEquals(100L, model.ready().markers[row().id])
            model.reconcile(listOf(row().copy(archived = false)))
            assertTrue(model.ready().markers.isEmpty()); assertTrue(writes.isEmpty())
        }
    }

    @Test fun matchingCachedSummariesDoNotRetireAcknowledgementsBeforeLaterStaleStreamUpdates() = runBlocking {
        withMediaOwner { owner ->
            val now = java.util.concurrent.atomic.AtomicLong(1_000)
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> }, Journal(), clock = now::get, monotonic = now::get)
            val chat = row().copy(favourite = true)
            model.reconcile(listOf(chat))
            model.set(chat, true)
            model.await { it.operations.isEmpty() && it.confirmed[chat.id] == true }
            model.reconcile(listOf(chat.copy(archived = true))) // Matching SDK snapshot.
            model.reconcile(listOf(chat)) // A different stream still holds the old tag.
            assertTrue(model.ready().effective(chat).archived)
            model.set(chat.copy(archived = true), false)
            model.await { it.operations.isEmpty() && it.markers.isEmpty() && it.confirmed[chat.id] == false }
            model.reconcile(listOf(chat)) // Also may be a snapshot from before either write.
            model.reconcile(listOf(chat.copy(archived = true)))
            assertFalse(model.ready().effective(chat.copy(archived = true)).archived)
            assertFalse(model.ready().effective(chat).favourite)
            now.addAndGet(20_001)
            model.reconcile(listOf(chat.copy(archived = true)))
            assertTrue(model.ready().confirmed.isEmpty())
            assertTrue(model.ready().effective(chat.copy(archived = true)).archived)
        }
    }

    @Test fun failedTagIntentIsProtectedAndCanReplayAfterAccountRestart() = runBlocking {
        val journal = Journal()
        withMediaOwner { owner ->
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> error("Synthetic offline") }, journal)
            model.set(row(), true)
            model.await { it.operations[row().id] is ArchiveOperation.Failed }
            assertTrue(model.ready().markers.isEmpty())
        }
        withMediaOwner { owner ->
            val writes = mutableListOf<Boolean>()
            val model = AccountArchive.create(owner, ArchiveBackend { _, value -> writes += value }, journal)
            assertTrue(model.ready().operations[row().id] is ArchiveOperation.Failed)
            model.retry().join(); model.await { it.operations.isEmpty() && it.markers[row().id] == 100L }
            assertEquals(listOf(true), writes)
        }
    }

    @Test fun failedProtectedWritesKeepPriorMarkersAndNeverDispatchTheTag() = runBlocking {
        withMediaOwner { owner ->
            val journal = Journal()
            val writes = mutableListOf<Boolean>()
            val model = AccountArchive.create(owner, ArchiveBackend { _, value -> writes += value }, journal)
            journal.fail = true; model.set(row(), true)
            model.await { it.operations[row().id] is ArchiveOperation.Unsaved }
            assertTrue(model.ready().markers.isEmpty()); assertTrue(writes.isEmpty())
            journal.fail = false; model.retry().join()
            model.await { it.markers[row().id] == 100L && it.operations.isEmpty() }
            assertEquals(listOf(true), writes)
        }
    }

    @Test fun aNewUserChoiceDuringATagWriteReplacesItsDurableFollowUpWithoutDroppingAnotherRoom() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<Pair<String, Boolean>>()
            val journal = Journal()
            val model = AccountArchive.create(owner, ArchiveBackend { room, value ->
                writes += room to value
                if (writes.size == 1) { entered.complete(Unit); release.await() }
            }, journal)
            model.set(row(), true); entered.await()
            model.set(row(), false)
            model.await { it.operations[row().id]?.intent?.archived == false }
            model.set(row("other"), true)
            model.await { "other" in it.operations }
            release.complete(Unit)
            model.await { it.operations.isEmpty() && "other" in it.markers }
            assertTrue(row().id !in model.ready().markers)
            assertEquals(listOf(row().id to true, row().id to false, "other" to true), writes)
        }
    }

    @Test fun aConfirmedTagSurvivesRestartBeforeItsEchoAndStopsOverridingAfterTheSharedGrace() = runBlocking {
        val journal = Journal()
        var now = 1_000L
        withMediaOwner { owner ->
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> }, journal, clock = { now }, monotonic = { now })
            model.set(row(), true); model.await { it.markers.isNotEmpty() && it.operations.isEmpty() }
        }
        withMediaOwner { owner ->
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> error("No tag write expected") }, journal, clock = { now }, monotonic = { now })
            model.reconcile(listOf(row()))
            assertTrue(model.ready().effective(row()).archived)
            now += 20_001
            model.reconcile(listOf(row()))
            assertFalse(model.ready().effective(row()).archived)
        }
    }

    @Test fun corruptMetadataIsRetainedAndRetryDoesNotOverwriteIt() = runBlocking {
        withMediaOwner { owner ->
            val journal = Journal().apply { value = "damaged protected record" }
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> fail("Must not dispatch") }, journal)
            model.set(row(), true); model.reconcile(listOf(row().copy(archived = true))); model.retry().join()
            assertEquals(ArchivePhase.Unavailable, model.phase.value)
            assertEquals("damaged protected record", journal.value)
        }
    }

    @Test fun aFailedIncomingRestoreIsRetriedEvenIfAnOwnMessageThenBecomesTheLatestEvent() = runBlocking {
        withMediaOwner { owner ->
            val journal = Journal()
            val writes = mutableListOf<Boolean>()
            val model = AccountArchive.create(owner, ArchiveBackend { _, value -> writes += value }, journal)
            model.set(row(), true); model.await { it.markers.isNotEmpty() && it.operations.isEmpty() }
            journal.fail = true
            model.reconcile(listOf(row().copy(latestIncoming = 101)))
            assertTrue(model.ready().operations[row().id] is ArchiveOperation.Unsaved)
            model.reconcile(listOf(row().copy(timestamp = 200, latestIncoming = null)))
            journal.fail = false; model.retry().join()
            model.await { it.markers.isEmpty() && it.operations.isEmpty() }
            assertEquals(listOf(true, false), writes)
        }
    }

    @Test fun retiringAnAccountKeepsItsPendingIntentAndRejectsLateAcknowledgement() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val journal = Journal()
            val model = AccountArchive.create(owner, ArchiveBackend { _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await() } }, journal)
            model.set(row(), true); entered.await()
            val closing = owner.closeAsync(); release.complete(Unit)
            assertEquals(AccountCloseResult.Closed, closing.await())
            assertTrue(model.ready().markers.isEmpty())
            withMediaOwner { other ->
                val untouched = AccountArchive.create(other, ArchiveBackend { _, _ -> }, Journal())
                assertTrue(untouched.ready().markers.isEmpty()); assertTrue(untouched.ready().operations.isEmpty())
            }
            assertTrue(journal.value!!.contains("pending")); assertFalse(journal.value!!.contains("\"synced\":true"))
        }
    }

    private fun AccountArchive.ready() = phase.value as ArchivePhase.Ready
    private suspend fun AccountArchive.await(test: (ArchivePhase.Ready) -> Boolean) = withTimeout(3_000) {
        phase.first { it is ArchivePhase.Ready && test(it) } as ArchivePhase.Ready
    }
    private class Journal : ArchiveJournal {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(value: String) { check(!fail); this.value = value }
    }
    companion object {
        fun row(id: String = "room") = ChatRow(id, "Synthetic room", null, emptyList(), true, false, false, 0, "Synthetic message body", 100)
    }
}
