// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each case states its facts as one named table, so a failure names every fact that differs. */
class ChatListSectionsTest {
    @Test fun favouritesAppearOnceInSyncedOrderAndHistoryKeepsSdkOrderEvenWithoutPreviews() {
        val rooms = listOf(room("recent", time = null), room("fav-a", favourite = true),
            room("older", time = 500), room("fav-b", favourite = true), room("last", time = 900))
        val result = ChatListSections.from(rooms, listOf("fav-b", "fav-a"), ChatScope.DIRECT, "")
        val shown = ids(result.favourites) + ids(result.history)
        assertEquals(mapOf(
            "favourites" to listOf("fav-b", "fav-a"),
            "history" to listOf("recent", "older", "last"),
            "every room shown once" to rooms.map { it.id }.sorted(),
        ), mapOf(
            "favourites" to ids(result.favourites),
            "history" to ids(result.history),
            "every room shown once" to shown.sorted(),
        ))
    }

    @Test fun scopesSearchAndArchiveApplyToBothSectionsAndFavouriteOnlyIsNotAnEmptyList() {
        val rooms = listOf(room("personal", favourite = true).copy(preview = "Needle"),
            room("group", favourite = true, dm = false), room("archive", favourite = true, archived = true), room("ordinary"))
        fun sections(scope: ChatScope, query: String) = ChatListSections.from(rooms, emptyList(), scope, query)
        val direct = sections(ChatScope.DIRECT, "needle")
        val groups = sections(ChatScope.GROUPS, "")
        val work = sections(ChatScope.ALL, "")
        assertEquals(mapOf(
            "direct search" to (listOf("personal") to emptyList<String>()), "direct search empty" to false,
            "groups" to (listOf("group") to emptyList<String>()),
            "work" to listOf("group", "ordinary", "personal"),
            "work search for a missing word empty" to true,
        ), mapOf(
            "direct search" to (ids(direct.favourites) to ids(direct.history)), "direct search empty" to direct.isEmpty,
            "groups" to (ids(groups.favourites) to ids(groups.history)),
            "work" to (ids(work.favourites) + ids(work.history)).sorted(),
            "work search for a missing word empty" to sections(ChatScope.ALL, "absent").isEmpty,
        ))
    }

    @Test fun aSyncedFavouriteTagChangeMovesTheSameRoomWithoutLosingItsUnreadStateOrDraft() {
        val original = room("person").copy(unread = 2, draft = "Unsent draft")
        fun sections(row: ChatRow) = ChatListSections.from(listOf(row), emptyList(), ChatScope.DIRECT, "")
        val before = sections(original)
        val starred = sections(original.copy(favourite = true))
        val moved = starred.favourites.single()
        val after = sections(moved.copy(favourite = false))
        assertEquals(mapOf(
            "before" to (emptyList<String>() to listOf("person")),
            "starred" to (listOf("person") to emptyList<String>()),
            "kept unread and draft" to (2L to "Unsent draft"),
            "unstarred row" to original,
        ), mapOf(
            "before" to (ids(before.favourites) to ids(before.history)),
            "starred" to (ids(starred.favourites) to ids(starred.history)),
            "kept unread and draft" to (moved.unread to moved.draft),
            "unstarred row" to after.history.single(),
        ))
    }

    @Test fun archivedScopeHasNoFavouriteGridAndManualUnreadNeverInventsAMessageCount() {
        val archived = room("saved", favourite = true, archived = true).copy(markedUnread = true, draft = "Retained")
        val result = ChatListSections.from(listOf(archived, room("active")), emptyList(), ChatScope.DIRECT, "", archived = true)
        val saved = result.history.single()
        assertEquals(mapOf("favourites" to emptyList<String>(), "history" to listOf("saved"),
            "unread mark, count and draft" to Triple(true, 0L, "Retained")),
            mapOf("favourites" to ids(result.favourites), "history" to ids(result.history),
            "unread mark, count and draft" to Triple(saved.hasUnread, saved.unread, saved.draft)))
    }

    private fun ids(rows: List<ChatRow>) = rows.map { it.id }

    private fun room(id: String, favourite: Boolean = false, dm: Boolean = true, archived: Boolean = false, time: Long? = null) =
        ChatRow(id, id, null, emptyList(), dm, favourite, archived, 0, null, time)
}
