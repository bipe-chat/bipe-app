// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.shared.BipeFavouritesOrder

enum class ChatScope { DIRECT, GROUPS, ALL }

/** One visible room belongs to one section. History keeps SDK order; favourites use the shared synced order. */
data class ChatListSections(val favourites: List<ChatRow>, val history: List<ChatRow>) {
    val isEmpty get() = favourites.isEmpty() && history.isEmpty()

    companion object {
        fun from(rows: List<ChatRow>, order: List<String>, scope: ChatScope, search: String, archived: Boolean = false): ChatListSections {
            val visible = rows.filter {
                it.archived == archived && when (scope) {
                    ChatScope.DIRECT -> it.isDm
                    ChatScope.GROUPS -> !it.isDm
                    ChatScope.ALL -> true
                } && (search.isEmpty() || it.name.contains(search, ignoreCase = true) || it.preview.orEmpty().contains(search, ignoreCase = true))
            }
            val (favourites, history) = visible.partition { it.favourite && !archived }
            return ChatListSections(BipeFavouritesOrder.sorted(favourites, order, { it.id }, { it.name }), history)
        }
    }
}
