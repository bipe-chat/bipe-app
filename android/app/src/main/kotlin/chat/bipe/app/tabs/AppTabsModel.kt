// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.AccountStartModel
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatScope
import chat.bipe.app.chatlist.ChatSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Type AccountSummary of spec app-tabs. */
data class AccountSummary(
    val userId: String,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val isCurrent: Boolean,
    val hasUnread: Boolean,
)

/**
 * Spec app-tabs (bipe-shared product/screens/app-tabs.json).
 *
 * Keeps one chat list per account for the life of the app, so the badges and the other-account dot follow every
 * account while only one shows.
 */
class AppTabsModel(
    private val start: AccountStartModel,
    private val scope: CoroutineScope,
    private val undecryptableText: String,
) {
    enum class Tab { CONVERSAS, GRUPOS, VOCE, SEARCH }

    data class State(
        val selectedTab: Tab = Tab.CONVERSAS,
        val accounts: List<AccountSummary> = emptyList(),
        val conversasUnread: Int = 0,
        val gruposAttention: Int = 0,
        val otherAccountUnread: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val chatLists = mutableMapOf<String, ChatListModel>()
    private val listJobs = mutableMapOf<String, Job>()
    private val latestChats = mutableMapOf<String, List<ChatSummary>>()
    private val profiles = mutableMapOf<String, Pair<String, String?>>()
    private var sessionIds: List<String> = emptyList()
    private var currentId: String? = null

    init {
        scope.launch {
            combine(start.sessions, start.userId) { sessions, userId -> sessions to userId }
                .collect { (sessions, userId) -> sessionsChanged(sessions, userId) }
        }
    }

    /** The chat list of an account, started on first use. */
    fun chatList(session: AccountSession): ChatListModel {
        chatLists[session.userId]?.let { return it }
        val model = ChatListModel(session, scope, undecryptableText)
        chatLists[session.userId] = model
        model.start()
        listJobs[session.userId] = scope.launch {
            model.chats.collect { chats ->
                latestChats[session.userId] = chats
                refresh()
            }
        }
        scope.launch { loadProfile(session) }
        return model
    }

    /** Action selectTab. A tap on Você always opens Você. */
    fun selectTab(tab: Tab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    /**
     * Action doubleTapVoce: the next account in accounts, wrapping around. With one account it does the same as a tap.
     * Returns true when it switched, so the caller plays the switch haptic (ADR 0134).
     */
    fun doubleTapVoce(): Boolean {
        selectTab(Tab.VOCE)
        val accounts = _state.value.accounts
        val index = accounts.indexOfFirst { it.isCurrent }
        if (accounts.size < 2 || index < 0) return false
        return switchAccount(accounts[(index + 1) % accounts.size].userId)
    }

    /** Action longPressVoce: opens account-switcher. Until the menu is built (bipe-chat/bipe#577, second step) it opens Você. */
    fun longPressVoce() {
        selectTab(Tab.VOCE)
    }

    /** Action switchAccount: that account's chats show in the tabs, and the choice is saved for the next start. */
    fun switchAccount(userId: String): Boolean {
        if (userId == currentId) return false
        start.switchAccount(userId)
        return true
    }

    private fun sessionsChanged(sessions: List<AccountSession>, userId: String?) {
        sessionIds = sessions.map { it.userId }
        currentId = userId
        (chatLists.keys - sessionIds.toSet()).forEach { gone ->
            chatLists.remove(gone)?.close()
            listJobs.remove(gone)?.cancel()
            latestChats.remove(gone)
            profiles.remove(gone)
        }
        sessions.forEach { chatList(it) }
        refresh()
    }

    private suspend fun loadProfile(session: AccountSession) {
        val displayName = runCatching { session.client.displayName() }.getOrNull().orEmpty()
        val avatarUrl = runCatching { session.client.avatarUrl() }.getOrNull()
        profiles[session.userId] = displayName to avatarUrl
        refresh()
    }

    private fun refresh() {
        val accounts = sessionIds.map { userId ->
            val profile = profiles[userId]
            AccountSummary(
                userId = userId,
                displayName = profile?.first.orEmpty(),
                handle = null,
                avatarUrl = profile?.second,
                isCurrent = userId == currentId,
                hasUnread = latestChats[userId].orEmpty().any { it.unreadCount > 0 },
            )
        }
        val chats = currentId?.let { latestChats[it] }.orEmpty()
        _state.value = _state.value.copy(
            accounts = accounts,
            conversasUnread = chats.count { it.scope == ChatScope.CONVERSAS && it.unreadCount > 0 },
            gruposAttention = chats.filter { it.scope == ChatScope.GRUPOS }.sumOf { it.mentionCount },
            otherAccountUnread = accounts.any { !it.isCurrent && it.hasUnread },
        )
    }
}
