// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.junit.Assert.*
import org.junit.Test

class ChatListSectionsTest {
    @Test fun favouritesAppearOnceInSyncedOrderAndHistoryKeepsSdkOrderEvenWithoutPreviews() {
        val rooms = listOf(room("recent", time = null), room("fav-a", favourite = true),
            room("older", time = 500), room("fav-b", favourite = true), room("last", time = 900))
        val result = ChatListSections.from(rooms, listOf("fav-b", "fav-a"), ChatScope.DIRECT, "")
        assertEquals(listOf("fav-b", "fav-a"), result.favourites.map { it.id })
        assertEquals(listOf("recent", "older", "last"), result.history.map { it.id })
        assertEquals(rooms.map { it.id }.toSet(), (result.favourites + result.history).map { it.id }.toSet())
        assertTrue(result.favourites.map { it.id }.intersect(result.history.map { it.id }.toSet()).isEmpty())
    }

    @Test fun scopesSearchAndArchiveApplyToBothSectionsAndFavouriteOnlyIsNotAnEmptyList() {
        val rooms = listOf(room("personal", favourite = true).copy(preview = "Needle"),
            room("group", favourite = true, dm = false), room("archive", favourite = true, archived = true), room("ordinary"))
        val direct = ChatListSections.from(rooms, emptyList(), ChatScope.DIRECT, "needle")
        assertEquals(listOf("personal"), direct.favourites.map { it.id })
        assertTrue(direct.history.isEmpty())
        assertFalse(direct.isEmpty)
        val groups = ChatListSections.from(rooms, emptyList(), ChatScope.GROUPS, "")
        assertEquals(listOf("group"), groups.favourites.map { it.id })
        assertTrue(groups.history.isEmpty())
        val work = ChatListSections.from(rooms, emptyList(), ChatScope.ALL, "")
        assertEquals(setOf("personal", "group", "ordinary"), (work.favourites + work.history).map { it.id }.toSet())
        assertTrue(ChatListSections.from(rooms, emptyList(), ChatScope.ALL, "absent").isEmpty)
    }

    @Test fun aSyncedFavouriteTagChangeMovesTheSameRoomWithoutLosingItsUnreadStateOrDraft() {
        val original = room("person").copy(unread = 2, draft = "Unsent draft")
        val history = ChatListSections.from(listOf(original), emptyList(), ChatScope.DIRECT, "")
        val starred = ChatListSections.from(listOf(original.copy(favourite = true)), emptyList(), ChatScope.DIRECT, "")
        assertTrue(history.favourites.isEmpty())
        assertTrue(starred.history.isEmpty())
        assertEquals(history.history.single().id, starred.favourites.single().id)
        assertEquals(2L, starred.favourites.single().unread)
        assertEquals("Unsent draft", starred.favourites.single().draft)
        val unstarred = ChatListSections.from(listOf(starred.favourites.single().copy(favourite = false)), emptyList(), ChatScope.DIRECT, "")
        assertEquals(original, unstarred.history.single())
    }

    private fun room(id: String, favourite: Boolean = false, dm: Boolean = true, archived: Boolean = false, time: Long? = null) =
        ChatRow(id, id, null, emptyList(), dm, favourite, archived, 0, null, time)
}
