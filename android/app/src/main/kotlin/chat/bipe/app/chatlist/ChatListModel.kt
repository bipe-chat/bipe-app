// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.previewText
import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.TimelineItemContent
import chat.bipe.app.common.toVectorDiff
import chat.bipe.shared.BipeOfficialAccounts
import chat.bipe.shared.BipeRowActivity
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
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.TaskHandle
import chat.bipe.app.voce.NotesRoom
import chat.bipe.shared.BipeChatRowName
import java.util.UUID

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
    val isMarkedUnread: Boolean = false,
    val latestIncomingMs: Long? = null,
) {
    val isUnread: Boolean get() = unreadCount > 0 || isMarkedUnread

    /** ADR 0141: a group of two stays a group; RoomInfo.isDm is the one DM test. */
    val scope: ChatScope
        get() = if (isDm) ChatScope.CONVERSAS else ChatScope.GRUPOS

    /** Spec chat-list isOfficial: a DM with an official Bipe account (ADR 0117), by exact user ID only. */
    val isOfficial: Boolean
        get() = isDm && BipeOfficialAccounts.isOfficial(peerUserId)
}

/** State scope of spec chat-list. TODAS: the Conversas tab of a company account, DMs and groups together (spec app-tabs). */
enum class ChatScope {
    CONVERSAS, GRUPOS, TODAS;

    /** True when a chat of [chat] scope shows in this one. */
    fun shows(chat: ChatScope): Boolean = this == TODAS || this == chat
}

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

    private val rooms = mutableListOf<Room>()
    private val subscriptionOwner = UUID.randomUUID().toString()
    /** One model belongs to one account session; absent previews must not erase a known time. */
    private val knownActivity = mutableMapOf<String, Long>()
    /** Rows take a newer message of the local timeline as their date and text (bipe-chat/bipe#592). */
    private val rowActivity = RowActivityScanner(session.client, scope, { it.previewText(undecryptableText, label) }) { refreshSummaries() }
    private val extraRoomIds = mutableSetOf<String>()
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult? = null
    private val handles = mutableListOf<TaskHandle>()
    private var summaryJob: Job? = null
    private val _markingRoomId = MutableStateFlow<String?>(null)
    val markingRoomId = _markingRoomId.asStateFlow()
    private val _readActionFailed = MutableStateFlow(false)
    val readActionFailed = _readActionFailed.asStateFlow()
    private var failedReadIntent: Pair<String, Boolean>? = null
    private var started = false
    private val clearChanges = scope.launch {
        ClearedChatsStore.revision.collect { refreshSummaries() }
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
                batch.forEach { update ->
                    // A room that moved or changed may have newer events than the ones read for its date.
                    when (update) {
                        is RoomListEntriesUpdate.Set -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.Insert -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushFront -> rowActivity.invalidate(update.value.id())
                        is RoomListEntriesUpdate.PushBack -> rowActivity.invalidate(update.value.id())
                        else -> Unit
                    }
                    rooms.applyDiff(update.toVectorDiff())
                }
                refreshSummaries()
                // Publish cached rows first; subscription enables SDK latest-event calculation and online catchup.
                session.roomSubscriptions.update(subscriptionOwner, rooms.map { it.id() } + archive.markers.value.keys)
            }
        }
    }

    /** Badges and both tabs use this one unfiltered joined stream. */
    private fun filter(): RoomListEntriesDynamicFilterKind = RoomListEntriesDynamicFilterKind.All(listOf(
        RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace,
        RoomListEntriesDynamicFilterKind.DeduplicateVersions,
    ))

    /** The phase of one scope: empty when the list loaded and the scope has no chat. */
    fun phase(scope: ChatScope, phase: Phase, chats: List<ChatSummary>): Phase = when {
        phase == Phase.LOADING -> Phase.LOADING
        chats.any { scope.shows(it.scope) } -> Phase.LOADED
        listLoaded -> Phase.EMPTY
        else -> Phase.LOADING
    }

    /** Action loadMore: the next page, when the list scrolls near its end. */
    @Suppress("UNUSED_PARAMETER")
    fun loadMore(tab: ChatScope) {
        entries?.controller()?.addOnePage()
    }

    /** Resolve actions in the immutable owner's room list. */
    fun currentChat(roomId: String): ChatSummary? = _chats.value.firstOrNull { it.roomId == roomId }

    /** Explicit public receipt first, then the manual unread flag. No optimistic fake count or startup gate. */
    fun setUnread(roomId: String, unread: Boolean) {
        if (_markingRoomId.value != null) return
        _markingRoomId.value = roomId
        _readActionFailed.value = false
        scope.launch {
            try {
                check(currentChat(roomId) != null || failedReadIntent == (roomId to unread))
                val room = session.client.getRoom(roomId) ?: error("room unavailable")
                val info = room.roomInfo()
                check(info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED && !info.isSpace)
                if (!unread) room.markAsRead(org.matrix.rustcomponents.sdk.ReceiptType.READ)
                room.setUnreadFlag(unread)
                failedReadIntent = null
                refreshSummaries()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failedReadIntent = roomId to unread
                _readActionFailed.value = true
            } finally { _markingRoomId.value = null }
        }
    }

    fun retryReadAction() { failedReadIntent?.let { (roomId, unread) -> setUnread(roomId, unread) } }

    suspend fun refreshNotes() {
        runCatching { notes.refresh() }
        refreshSummaries()
    }

    suspend fun openNotes(title: String): String = try { notes.open(title) } finally { refreshSummaries() }

    fun close() {
        clearChanges.cancel()
        rowActivity.close()
        handles.forEach { it.cancel() }
        summaryJob?.cancel()
        session.releaseRoomSubscriptions(subscriptionOwner)
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
        for (id in (extraRoomIds - loadedIds).sorted()) runCatching { session.client.getRoom(id) }.getOrNull()?.let { snapshot += it }
        summaryJob?.cancel()
        summaryJob = scope.launch {
            val summaries = snapshot.filter { it.id() != notes.roomId }.mapNotNull { summary(it) }
            val visible = retainingKnownActivity(NativeDmSelection.visible(summaries, session))
            archive.reconcile(visible)
            // SDK order includes server recency stamps and local echoes, even when previews have no timestamp.
            _chats.value = visible
            updatePhase()
        }
    }

    /**
     * The row date and text (BipeRowActivity): a message of the local timeline newer than the SDK latest event, else the
     * SDK latest event, else the newest local event, which includes undecryptable events (bipe-chat/bipe#592), else a time
     * known earlier in this account session. The SDK keeps an older latest event when a message arrived before its room
     * key; the timeline decrypts it later.
     */
    private fun retainingKnownActivity(summaries: List<ChatSummary>): List<ChatSummary> = summaries.map { summary ->
        val sdk = summary.lastActivity?.let {
            BipeRowActivity.SdkLatest(it, summary.lastMessage, isIncoming = summary.latestIncomingMs != null)
        }
        val local = rowActivity.localEvents(summary.roomId).orEmpty()
        val row = BipeRowActivity.resolve(sdk, local, knownActivity[summary.roomId])
        row.timestampMs?.let { knownActivity[summary.roomId] = it }
        summary.copy(lastActivity = row.timestampMs, lastMessage = row.previewText, latestIncomingMs = row.latestIncomingMs)
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
                if (latest.content is TimelineItemContent.RtcNotification && !latest.isOwn &&
                    chat.bipe.app.calls.MissedCalls.isMissed(session.userId, info.id)) {
                    lastMessage = label(R.string.bipe_call_missed)
                }
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
        val name = BipeChatRowName.resolve(info.id, info.displayName, peer?.displayName, peer?.userId, unnamedChatText,
            dmRoomName = if (info.isDm) info.rawName else null)
        return ChatSummary(
            roomId = info.id,
            name = name,
            // ADR 0141: a DM shows the person's photo, never m.room.avatar.
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
            isMarkedUnread = info.isMarkedUnread,
            latestIncomingMs = latestIncomingMs,
        )
    }

    private companion object {
        const val PAGE_SIZE = 50u
    }
}
