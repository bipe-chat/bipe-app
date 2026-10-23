// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.shared.BipeChatArchiveStore
import chat.bipe.shared.generated.BipeChatArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One instance per signed-in account. No server mutation or history clearing. Call on the model's main scope. */
class ChatArchiveModel(private val store: BipeChatArchiveStore) {
    private val _markers = MutableStateFlow<Map<String, Long>>(emptyMap())
    val markers = _markers.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var pending: Pair<ChatSummary, Boolean>? = null

    init { reload() }

    fun set(chat: ChatSummary, archived: Boolean) {
        pending = chat to archived
        runCatching {
            val next = store.load().toMutableMap()
            if (archived) next[chat.roomId] = (chat.lastActivity ?: 0L).coerceAtLeast(0L) else next.remove(chat.roomId)
            store.save(next)
            _markers.value = next
            pending = null
            _failed.value = false
        }.onFailure { _failed.value = true }
    }

    fun reconcile(chats: List<ChatSummary>) {
        if (_failed.value) return
        val next = _markers.value.toMutableMap()
        for (chat in chats) {
            if (!BipeChatArchive.isArchived(next[chat.roomId], chat.latestIncomingMs)) next.remove(chat.roomId)
        }
        if (next == _markers.value) return
        runCatching { store.save(next); _markers.value = next }.onFailure { _failed.value = true }
    }

    fun retry(chats: List<ChatSummary>) {
        reload()
        if (_failed.value) return
        pending?.let { (chat, archived) -> set(chat, archived) }
        reconcile(chats)
    }

    private fun reload() {
        runCatching { _markers.value = store.load(); _failed.value = false }.onFailure { _failed.value = true }
    }
}
