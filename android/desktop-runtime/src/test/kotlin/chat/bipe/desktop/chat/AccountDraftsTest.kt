// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.*
import chat.bipe.desktop.storage.*
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AccountDraftsTest {
    private val root = Files.createTempDirectory("bipe-drafts-")
    private val repository = AccountRepository(root, MemoryVault(), MemoryProtection())
    private val owners = mutableListOf<AccountLifetime>()
    @After fun cleanup() = runBlocking { owners.forEach { it.closeAsync().await() }; root.toFile().deleteRecursively(); Unit }

    @Test(timeout = 20_000) fun coalescesEditsAndDrainsTheLatestTextBeforeReleasingTheSlot() = runBlocking {
        val store = Store()
        val (owner, drafts) = createDrafts(store)
        val composer = drafts.room("same-room")
        ready(composer)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        store.beforeSave = { body -> if (body == "first") { entered.complete(Unit); release.await() } }
        composer.edit("first")
        entered.await()
        composer.edit("second"); composer.edit("latest")
        val closing = owner.closeAsync()
        expect(StorageCode.SLOT_BUSY) { repository.open(owner.lease.slot) }
        release.complete(Unit)
        assertEquals(AccountCloseResult.Closed, closing.await())
        assertEquals(listOf("first", "latest"), store.writes.map { it.second })
        assertEquals("latest", store.saved["same-room"])
        repository.open(owner.lease.slot).close()
    }

    @Test(timeout = 20_000) fun failedWritesRetainTheirOwnerLockAndRetryTheLatestText() = runBlocking {
        val store = Store()
        val (owner, drafts) = createDrafts(store)
        val composer = drafts.room("room")
        ready(composer)
        store.failSave = true
        composer.edit("keep this")
        failed(composer)
        assertTrue(owner.closeAsync().await() is AccountCloseResult.Blocked)
        expect(StorageCode.SLOT_BUSY) { repository.open(owner.lease.slot) }
        assertEquals("keep this", (composer.state.value as ComposerState.Editing).text)
        store.failSave = false
        assertEquals(AccountCloseResult.Closed, AccountLifetime.retryCleanup(owner.epoch)!!.await())
        assertEquals("keep this", store.saved["room"])
    }

    @Test(timeout = 20_000) fun identicalRoomIdsInDifferentAccountsCannotShareDraftsOrLateEdits() = runBlocking {
        val first = Store(); val second = Store()
        val (ownerA, a) = createDrafts(first); val (_, b) = createDrafts(second)
        val ca = a.room("same-room"); val cb = b.room("same-room")
        ready(ca); ready(cb)
        ca.edit("personal"); cb.edit("work")
        saved(ca); saved(cb)
        cb.accepted(ca.submission()!!)
        assertEquals("work", (cb.state.value as ComposerState.Editing).text)
        assertEquals(AccountCloseResult.Closed, ownerA.closeAsync().await())
        ca.edit("late personal")
        assertEquals("personal", first.saved["same-room"])
        assertEquals("work", second.saved["same-room"])
    }

    @Test(timeout = 20_000) fun aSuccessfulSendCannotEraseAnEditAwayAndBackToTheSameBody() = runBlocking {
        val (_, drafts) = createDrafts(Store())
        val composer = drafts.room("room"); ready(composer)
        composer.edit("send me")
        val submitted = composer.submission()!!
        composer.edit("new edit"); composer.edit("send me")
        composer.accepted(submitted)
        assertEquals("send me", (composer.state.value as ComposerState.Editing).text)
        composer.accepted(composer.submission()!!)
        saved(composer)
        assertEquals("", (composer.state.value as ComposerState.Editing).text)
        assertTrue(drafts.previews.value.containsKey("room"))
        assertNull(drafts.previews.value["room"])
    }

    @Test(timeout = 20_000) fun anOwnedSendAcknowledgementCanClearItsDraftDuringRetirement() = runBlocking {
        val store = Store()
        val (owner, drafts) = createDrafts(store)
        val composer = drafts.room("room"); ready(composer)
        composer.edit("accepted by SDK")
        val submitted = composer.submission()!!
        // Room/send resources precede the writer in teardown, even after UI publication has retired.
        owner.own(AccountResource { composer.accepted(submitted) })
        assertEquals(AccountCloseResult.Closed, owner.closeAsync().await())
        assertEquals("", store.saved["room"])
        composer.edit("late UI edit")
        assertEquals("", store.saved["room"])
    }

    @Test(timeout = 20_000) fun reopeningARoomUsesItsLatestDraftAndRecreatingAnOwnerLoadsStorage() = runBlocking {
        val store = Store()
        val (owner, drafts) = createDrafts(store)
        val composer = drafts.room("room"); ready(composer)
        composer.edit("survives reopen")
        assertSame(composer, drafts.room("room"))
        assertEquals(AccountCloseResult.Closed, owner.closeAsync().await())
        // The fake store stands in for the same encrypted SDK database on a native restart.
        val (_, restored) = createDrafts(store, owner.lease.slot)
        assertEquals("survives reopen", ready(restored.room("room")).text)
    }

    @Test(timeout = 20_000) fun failedLoadAndUnsupportedDraftsCannotBeOverwrittenByTypingOrSend() = runBlocking {
        val store = Store().apply { failLoad = true }
        val (_, drafts) = createDrafts(store)
        val composer = drafts.room("room")
        withTimeout(2_000) { composer.state.first { it == ComposerState.LoadFailed } }
        composer.edit("must not replace unread draft")
        assertNull(composer.submission())
        assertTrue(store.writes.isEmpty())
        store.failLoad = false; store.unsupported = true
        composer.retry()
        withTimeout(2_000) { composer.state.first { it == ComposerState.Unsupported } }
        composer.edit("must not flatten a reply")
        assertNull(composer.submission())
        assertTrue(store.writes.isEmpty())
    }

    private suspend fun createDrafts(store: Store, slot: AccountSlot? = null): Pair<AccountLifetime, AccountDrafts> {
        val owner = AccountLifetime(slot?.let(repository::open) ?: repository.provision()).also { owners += it }
        return owner to owner.own(AccountDrafts(owner, store))
    }
    private suspend fun ready(room: RoomComposer) = withTimeout(2_000) { room.state.first { it is ComposerState.Editing } as ComposerState.Editing }
    private suspend fun saved(room: RoomComposer) = withTimeout(2_000) { room.state.first {
        it is ComposerState.Editing && it.persistence == DraftPersistence.SAVED
    } }
    private suspend fun failed(room: RoomComposer) = withTimeout(2_000) { room.state.first {
        it is ComposerState.Editing && it.persistence == DraftPersistence.FAILED
    } }
    private class Store : ComposerDraftStore {
        val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
        val writes = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        @Volatile var failLoad = false
        @Volatile var failSave = false
        @Volatile var unsupported = false
        var beforeSave: suspend (String) -> Unit = {}
        override suspend fun load(roomId: String): SavedComposerDraft {
            if (failLoad) throw IllegalStateException("synthetic-private-body")
            return if (unsupported) SavedComposerDraft.Unsupported else SavedComposerDraft.Text(saved[roomId].orEmpty())
        }
        override suspend fun save(roomId: String, body: String) {
            beforeSave(body)
            if (failSave) throw IllegalStateException("synthetic-private-body")
            writes += roomId to body
            saved[roomId] = body
        }
    }
}
