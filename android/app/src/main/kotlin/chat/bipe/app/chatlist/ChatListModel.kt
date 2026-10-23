// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.previewText
import chat.bipe.app.common.toVectorDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListFilterCategory
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.TaskHandle
import chat.bipe.app.voce.NotesRoom

/** Type ChatSummary of spec chat-list. */
data class ChatSummary(
    val roomId: String,
    val name: String,
    val avatarUrl: String?,
    val lastMessage: String?,
    val lastActivity: Long?,
    val unreadCount: Int,
    val isDirect: Boolean,
    /** Joined and invited members. Not in the spec type: it decides the scope. */
    val memberCount: Int,
    /** Unread mentions and replies, for the Grupos badge (spec app-tabs gruposAttention). */
    val mentionCount: Int,
    /** The other member of a DM, for the avatar account-type lookup. */
    val peerUserId: String? = null,
    /** RoomInfo.isDm, the one DM test (ADR 0141). */
    val isDm: Boolean = false,
    val isMuted: Boolean = false,
    val latestIncomingMs: Long? = null,
) {
    /** ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test. */
    val scope: ChatScope
        get() = if (isDm) ChatScope.CONVERSAS else ChatScope.GRUPOS
}

/** State scope of spec chat-list. */
enum class ChatScope { CONVERSAS, GRUPOS }

/** Spec chat-list (bipe-shared product/screens/chat-list.json). */
class ChatListModel(
    val session: AccountSession,
    private val scope: CoroutineScope,
    private val undecryptableText: String,
    private val unnamedChatText: String,
    /** Resolves the media kind labels of the previews. */
    private val label: (Int) -> String,
    val notes: NotesRoom,
    val archive: ChatArchiveModel,
) {
    enum class Phase { LOADING, EMPTY, LOADED }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()
    private val _unreadScopes = MutableStateFlow<Set<ChatScope>>(emptySet())
    val unreadScopes = _unreadScopes.asStateFlow()
    private val _unreadChats = MutableStateFlow<Map<ChatScope, List<ChatSummary>>>(emptyMap())
    val unreadChats = _unreadChats.asStateFlow()
    private val _unreadLoading = MutableStateFlow<Set<ChatScope>>(emptySet())
    val unreadLoading = _unreadLoading.asStateFlow()
    private val unreadLists = mutableMapOf<ChatScope, FilteredRoomList>()

    private val rooms = mutableListOf<Room>()
    private val extraRoomIds = mutableSetOf<String>()
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult? = null
    private val handles = mutableListOf<TaskHandle>()
    private var summaryJob: Job? = null
    private var started = false
    private val clearChanges = scope.launch {
        ClearedChatsStore.revision.collect {
            refreshSummaries()
            unreadLists.values.forEach { it.refresh() }
        }
    }

    /** Subscribes to the room list service: joined chats, newest activity first. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            refreshNotes()
            val roomList = runCatching { session.syncService.roomListService().allRooms() }.getOrNull() ?: return@launch
            val loading = Channel<RoomListLoadingState>(Channel.UNLIMITED)
            runCatching {
                roomList.loadingState(object : RoomListLoadingStateListener {
                    override fun onUpdate(state: RoomListLoadingState) {
                        loading.trySend(state)
                    }
                })
            }.getOrNull()?.let { result ->
                handles += result.stateStream
                onLoadingState(result.state)
            }
            val updates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
            val result = roomList.entriesWithDynamicAdapters(
                PAGE_SIZE,
                object : RoomListEntriesListener {
                    override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                        updates.trySend(roomEntriesUpdate)
                    }
                },
            )
            result.controller().setFilter(filter())
            handles += result.entriesStream()
            entries = result
            launch {
                for (state in loading) onLoadingState(state)
            }
            for (batch in updates) {
                batch.forEach { rooms.applyDiff(it.toVectorDiff()) }
                refreshSummaries()
            }
        }
    }

    /** Badges keep the unfiltered joined stream while each tab has its own unread subscription. */
    private fun filter(): RoomListEntriesDynamicFilterKind = RoomListEntriesDynamicFilterKind.All(listOf(
        RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace,
        RoomListEntriesDynamicFilterKind.DeduplicateVersions,
    ))

    fun toggleUnread(tab: ChatScope) {
        if (tab in _unreadScopes.value) { _unreadScopes.value -= tab; return }
        _unreadScopes.value += tab
        if (unreadLists.containsKey(tab)) return
        _unreadLoading.value += tab
        val list = FilteredRoomList(scope) { found ->
            val summaries = found.filter { it.id() != notes.roomId }.mapNotNull { summary(it) }
            val visible = NativeDmSelection.visible(summaries, session)
            archive.reconcile(visible)
            _unreadChats.value += (tab to visible)
            _unreadLoading.value -= tab
        }
        unreadLists[tab] = list
        scope.launch { list.start(session, RoomListEntriesDynamicFilterKind.All(listOf(
            RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace,
            RoomListEntriesDynamicFilterKind.DeduplicateVersions,
            RoomListEntriesDynamicFilterKind.Category(if (tab == ChatScope.CONVERSAS) RoomListFilterCategory.PEOPLE else RoomListFilterCategory.GROUP),
            RoomListEntriesDynamicFilterKind.Unread,
        ))) }
    }

    /** The phase of one scope: empty when the list loaded and the scope has no chat. */
    fun phase(scope: ChatScope, phase: Phase, chats: List<ChatSummary>): Phase = when {
        phase == Phase.LOADING -> Phase.LOADING
        chats.any { it.scope == scope } -> Phase.LOADED
        listLoaded -> Phase.EMPTY
        else -> Phase.LOADING
    }

    /** Action loadMore: the next page, when the list scrolls near its end. */
    fun loadMore(tab: ChatScope) {
        if (tab in _unreadScopes.value) unreadLists[tab]?.loadMore()
        else entries?.controller()?.addOnePage()
    }

    suspend fun refreshNotes() {
        runCatching { notes.refresh() }
        refreshSummaries()
    }

    suspend fun openNotes(title: String): String = try { notes.open(title) } finally { refreshSummaries() }

    fun close() {
        clearChanges.cancel()
        unreadLists.values.forEach { it.close() }
        handles.forEach { it.cancel() }
        summaryJob?.cancel()
    }

    private fun onLoadingState(state: RoomListLoadingState) {
        if (state is RoomListLoadingState.Loaded) listLoaded = true
        updatePhase()
    }

    private fun updatePhase() {
        _phase.value = when {
            _chats.value.isNotEmpty() -> Phase.LOADED
            listLoaded -> Phase.EMPTY
            else -> Phase.LOADING
        }
    }

    private fun refreshSummaries() {
        extraRoomIds += archive.markers.value.keys
        val snapshot = rooms.toMutableList()
        val loadedIds = snapshot.map { it.id() }.toSet()
        for (id in extraRoomIds - loadedIds) runCatching { session.client.getRoom(id) }.getOrNull()?.let { snapshot += it }
        summaryJob?.cancel()
        summaryJob = scope.launch {
            val summaries = snapshot.filter { it.id() != notes.roomId }.mapNotNull { summary(it) }
            val visible = NativeDmSelection.visible(summaries, session)
            archive.reconcile(visible)
            _chats.value = visible.sortedByDescending { it.lastActivity ?: Long.MIN_VALUE }
            updatePhase()
        }
    }

    suspend fun summary(room: Room): ChatSummary? {
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return null
        if (info.membership != org.matrix.rustcomponents.sdk.Membership.JOINED || info.isSpace) return null
        var lastMessage: String? = null
        var lastActivity: Long? = null
        var latestIncomingMs: Long? = null
        when (val latest = room.latestEvent()) {
            is LatestEventValue.Remote -> {
                lastMessage = latest.content.previewText(undecryptableText, label)
                lastActivity = latest.timestamp.toLong()
                if (!latest.isOwn && lastMessage != null) latestIncomingMs = lastActivity
            }
            is LatestEventValue.Local -> {
                lastMessage = latest.content.previewText(undecryptableText, label)
                lastActivity = latest.timestamp.toLong()
            }
            is LatestEventValue.RemoteInvite -> lastActivity = latest.timestamp.toLong()
            LatestEventValue.None -> Unit
        }
        val peer = if (info.isDm) info.heroes.firstOrNull { it.userId != session.userId } else null
        val peerName = peer?.displayName?.takeIf { it.isNotBlank() }
            ?: peer?.userId?.let { chat.bipe.app.people.Person.localpart(it) } ?: unnamedChatText
        return ChatSummary(
            roomId = info.id,
            name = if (info.isDm) peerName else info.displayName ?: info.id,
            avatarUrl = if (info.isDm) peer?.avatarUrl else info.avatarUrl,
            lastMessage = lastMessage,
            lastActivity = lastActivity,
            unreadCount = info.numUnreadMessages.toInt(),
            isDirect = info.isDirect,
            memberCount = info.activeMembersCount.toInt(),
            mentionCount = info.numUnreadMentions.toInt(),
            peerUserId = peer?.userId,
            isDm = info.isDm,
            isMuted = info.cachedUserDefinedNotificationMode == RoomNotificationMode.MUTE,
            latestIncomingMs = latestIncomingMs,
        )
    }

    private companion object {
        const val PAGE_SIZE = 50u
    }
}
