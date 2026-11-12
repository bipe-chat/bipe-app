// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class EditActionsTest {
    @Test fun startEndsAReplyAndCancelRestoresItsTextWithoutSavingEditText() = runBlocking {
        withMediaOwner { owner ->
            val fixture = fixture(owner)
            fixture.composer.edit("unsent draft"); fixture.composer.replyTo("\$reply")
            fixture.saved()
            assertTrue(fixture.actions.start("row"))
            assertEquals("Original message", fixture.actions.phase.value.draft!!.text)
            fixture.actions.change("changed message")
            assertTrue(fixture.actions.cancel())
            fixture.saved()
            assertEquals("unsent draft", fixture.normal().text)
            assertNull(fixture.normal().replyTo)
            assertEquals(SavedComposerDraft.Text("unsent draft"), fixture.store.value)
        }
    }

    @Test fun blankAndUnchangedEditsCannotSendAndRepeatClicksDispatchOneImmutableTarget() = runBlocking {
        withMediaOwner { owner ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val fixture = fixture(owner) { event, body ->
                assertEquals("\$own", event); assertEquals("Updated", body)
                calls.incrementAndGet(); entered.complete(Unit); release.await()
            }
            fixture.actions.start("row"); fixture.actions.save()
            fixture.actions.change("   "); fixture.actions.save()
            fixture.actions.change(" Original message "); fixture.actions.save()
            assertEquals(0, calls.get())
            fixture.actions.change(" Updated "); fixture.actions.save(); entered.await()
            repeat(10) { fixture.actions.save() }
            fixture.actions.change("must not replace the submission")
            assertFalse(fixture.actions.cancel()); assertFalse(fixture.actions.start("row"))
            release.complete(Unit)
            fixture.actions.phase.first { it == EditPhase.Idle }
            assertEquals(1, calls.get())
        }
    }

    @Test fun failedSaveKeepsTheEditedTextAndRetryNeverErasesTheUnsentDraft() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val fixture = fixture(owner) { event, body ->
                assertEquals("\$own", event); assertEquals("Updated", body)
                if (calls.incrementAndGet() == 1) error("Synthetic edit failure")
            }
            fixture.composer.edit("keep this draft"); fixture.saved()
            fixture.actions.start("row"); fixture.actions.change("Updated"); fixture.actions.save()
            fixture.actions.phase.first { it is EditPhase.Failed }
            assertEquals("Updated", fixture.actions.phase.value.draft!!.text)
            assertEquals("keep this draft", fixture.normal().text)
            fixture.actions.save(); fixture.actions.phase.first { it == EditPhase.Idle }
            assertEquals(2, calls.get())
            assertEquals(SavedComposerDraft.Text("keep this draft"), fixture.store.value)
        }
    }

    @Test fun permissionsAndTargetIdentityAreRecheckedAfterTheMenuAndBeforeSaving() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val fixture = fixture(owner) { _, _ -> calls.incrementAndGet(); Unit }
            for (invalid in listOf(message().copy(own = false), message().copy(canEdit = false),
                message().copy(kind = MessageRow.Kind.DELETED), message().copy(eventId = null),
                message().copy(delivery = MessageRow.Delivery.SENDING))) {
                fixture.current = invalid; assertFalse(fixture.actions.start("row"))
            }
            fixture.current = message(); fixture.actions.start("row"); fixture.actions.change("Updated")
            fixture.current = message().copy(eventId = "\$replacement")
            fixture.actions.save(); fixture.actions.phase.first { it is EditPhase.Failed }
            assertEquals(0, calls.get())
            fixture.actions.cancel(); fixture.current = message()
            fixture.actions.start("row"); fixture.actions.change("Updated"); fixture.current = null
            fixture.actions.save(); fixture.actions.phase.first { it is EditPhase.Failed }
            assertEquals(0, calls.get())
        }
    }

    @Test fun aRetiredRoomCannotPublishItsLateSaveIntoAnotherAccount() = runBlocking {
        withMediaOwner { first -> withMediaOwner { second ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val a = fixture(first) { _, _ -> withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val b = fixture(second)
            a.composer.edit("personal"); b.composer.edit("work"); a.saved(); b.saved()
            a.actions.start("row"); a.actions.change("Updated"); a.actions.save(); entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { a.group.close() }
            release.complete(Unit); closing.await()
            assertTrue(a.actions.phase.value is EditPhase.Saving)
            assertFalse(a.actions.start("row"))
            assertEquals(EditPhase.Idle, b.actions.phase.value)
            assertEquals("personal", a.normal().text); assertEquals("work", b.normal().text)
        } }
    }

    @Test fun leavingTheRoomKeepsTheDurableDraftInsteadOfTheTransientMessageEdit() = runBlocking {
        withMediaOwner { owner ->
            val fixture = fixture(owner)
            fixture.composer.edit("durable unsent text"); fixture.saved()
            fixture.actions.start("row"); fixture.actions.change("transient edit text")
            fixture.group.close()
            assertEquals("durable unsent text", fixture.normal().text)
            assertEquals(SavedComposerDraft.Text("durable unsent text"), fixture.store.value)
        }
    }

    @Test fun pendingSendsAndUnsupportedStoredDraftsCannotEnterEditMode() = runBlocking {
        withMediaOwner { owner ->
            val fixture = fixture(owner)
            fixture.allowed = false; assertFalse(fixture.actions.start("row"))
            fixture.allowed = true; assertTrue(fixture.actions.start("row"))
            fixture.actions.cancel()
        }
        withMediaOwner { owner ->
            val store = Store(SavedComposerDraft.Unsupported)
            val drafts = owner.own(AccountDrafts(owner, store)); val composer = drafts.room("room")
            composer.state.first { it != ComposerState.Loading }
            val actions = EditActions(AccountTaskGroup.create(owner), composer, { message() }, { true }) { _, _ -> fail("Must preserve unsupported draft") }
            assertFalse(actions.start("row")); assertEquals(SavedComposerDraft.Unsupported, store.value)
        }
    }

    @Test fun oldFieldAndSaveCallbacksCannotEditANewSessionWithTheSameRowIdentity() = runBlocking {
        withMediaOwner { owner ->
            val calls = AtomicInteger()
            val fixture = fixture(owner) { _, _ -> calls.incrementAndGet(); Unit }
            fixture.actions.start("row")
            val oldFrame = fixture.actions.phase.value.draft!!
            fixture.actions.change("Updated", oldFrame); fixture.actions.save(oldFrame)
            fixture.actions.phase.first { it == EditPhase.Idle }
            fixture.actions.save(oldFrame)
            assertEquals(EditPhase.Idle, fixture.actions.phase.value)
            assertTrue(fixture.actions.start("row"))
            fixture.actions.change("Current edit")
            fixture.actions.change("Late old text", oldFrame); fixture.actions.save(oldFrame)
            assertTrue(fixture.actions.phase.value is EditPhase.Composing)
            assertEquals("Current edit", fixture.actions.phase.value.draft!!.text)
            assertEquals(1, calls.get())
        }
    }

    private class Store(initial: SavedComposerDraft = SavedComposerDraft.Text("")) : ComposerDraftStore {
        @Volatile var value = initial
        override suspend fun load(roomId: String) = value
        override suspend fun save(roomId: String, draft: SavedComposerDraft.Text) { value = draft }
    }
    private class Fixture(val store: Store, val composer: RoomComposer, val group: AccountTaskGroup, perform: suspend (String, String) -> Unit) {
        var current: MessageRow? = message()
        var allowed = true
        val actions = EditActions(group, composer, { current?.takeIf { row -> row.id == it } }, { allowed }, perform)
        fun normal() = composer.state.value as ComposerState.Editing
        suspend fun saved() { withTimeout(2_000) { composer.state.first { it is ComposerState.Editing && it.persistence == DraftPersistence.SAVED } } }
    }
    private suspend fun fixture(owner: AccountLifetime, perform: suspend (String, String) -> Unit = { _, _ -> }): Fixture {
        val store = Store(); val drafts = owner.own(AccountDrafts(owner, store)); val composer = drafts.room("room")
        composer.state.first { it is ComposerState.Editing }
        return Fixture(store, composer, AccountTaskGroup.create(owner), perform)
    }
    companion object {
        private fun message() = MessageRow("row", "You", true, "Original message", MessageRow.Kind.TEXT, 1,
            MessageRow.Delivery.SENT, eventId = "\$own", canEdit = true)
    }
}
