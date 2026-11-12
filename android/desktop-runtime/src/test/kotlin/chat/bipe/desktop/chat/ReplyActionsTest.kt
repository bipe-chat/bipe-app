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

class ReplyActionsTest {
    private val root = Files.createTempDirectory("bipe-reply-actions-")
    private val repository = AccountRepository(root, MemoryVault(), MemoryProtection())
    private val owners = mutableListOf<AccountLifetime>()
    @After fun cleanup() = runBlocking { owners.forEach { it.closeAsync().await() }; root.toFile().deleteRecursively(); Unit }

    @Test(timeout = 20_000) fun commandsRecheckRemovedRedactedAndUnsentRowsAtDispatch() = runBlocking {
        val fixture = fixture()
        val composer = fixture.composer
        composer.edit("existing text")
        assertTrue(fixture.actions.start("row"))
        assertEquals("\$original", (composer.state.value as ComposerState.Editing).replyTo)
        composer.cancelReply()
        fixture.current = fixture.current!!.copy(canReply = false)
        assertFalse(fixture.actions.start("row"))
        fixture.current = row().copy(kind = MessageRow.Kind.DELETED)
        assertFalse(fixture.actions.start("row"))
        fixture.current = row().copy(delivery = MessageRow.Delivery.FAILED)
        assertFalse(fixture.actions.start("row"))
        fixture.current = row().copy(eventId = null)
        assertFalse(fixture.actions.start("row"))
        fixture.current = null
        assertFalse(fixture.actions.start("row"))
        assertEquals("existing text", (composer.state.value as ComposerState.Editing).text)
        assertNull((composer.state.value as ComposerState.Editing).replyTo)
    }

    @Test(timeout = 20_000) fun identicalRowsInTwoAccountsAndRetiredMenusCannotCrossOwners() = runBlocking {
        val first = fixture(); val second = fixture()
        first.composer.edit("personal"); second.composer.edit("work")
        first.actions.start("row")
        assertNull((second.composer.state.value as ComposerState.Editing).replyTo)
        assertEquals(AccountCloseResult.Closed, first.owner.closeAsync().await())
        assertFalse(first.actions.start("row"))
        assertEquals("work", (second.composer.state.value as ComposerState.Editing).text)
        assertTrue(second.actions.start("row"))
    }

    @Test(timeout = 20_000) fun unsupportedStoredDraftsCannotBeFlattenedByReplyCommands() = runBlocking {
        val fixture = fixture(SavedComposerDraft.Unsupported)
        assertFalse(fixture.actions.start("row"))
        assertEquals(ComposerState.Unsupported, fixture.composer.state.value)
    }

    private class Fixture(val owner: AccountLifetime, val composer: RoomComposer, group: AccountTaskGroup) {
        var current: MessageRow? = row()
        val actions = ReplyActions(group, composer) { current?.takeIf { row -> row.id == it } }
    }
    private suspend fun fixture(initial: SavedComposerDraft = SavedComposerDraft.Text("")): Fixture {
        val owner = AccountLifetime(repository.provision()).also { owners += it }
        val drafts = owner.own(AccountDrafts(owner, object : ComposerDraftStore {
            override suspend fun load(roomId: String) = initial
            override suspend fun save(roomId: String, draft: SavedComposerDraft.Text) = Unit
        }))
        val composer = drafts.room("room")
        withTimeout(2_000) { composer.state.first { it !is ComposerState.Loading } }
        return Fixture(owner, composer, AccountTaskGroup.create(owner))
    }
    companion object {
        private fun row() = MessageRow("row", "Peer", false, "Original", MessageRow.Kind.TEXT, 1,
            MessageRow.Delivery.SENT, eventId = "\$original", canReply = true)
    }
}
