// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.previewText
import chat.bipe.app.common.toVectorDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.TaskHandle

/** Type ChatSummary of spec chat-list. */
data class ChatSummary(
    val roomId: String,
    val name: String,
    val avatarUrl: String?,
    val lastMessage: String?,
    val lastActivity: Long?,
    val unreadCount: Int,
    val isDirect: Boolean,
)

/** Spec chat-list (bipe-shared product/screens/chat-list.json). */
class ChatListModel(
    val session: AccountSession,
    private val scope: CoroutineScope,
    private val undecryptableText: String,
    /** Resolves the media kind labels of the previews. */
    private val label: (Int) -> String,
) {
    enum class Phase { LOADING, EMPTY, LOADED }

    private val _phase = MutableStateFlow(Phase.LOADING)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val rooms = mutableListOf<Room>()
    private var listLoaded = false
    private var entries: RoomListEntriesWithDynamicAdaptersResult? = null
    private val handles = mutableListOf<TaskHandle>()
    private var summaryJob: Job? = null
    private var started = false

    /** Subscribes to the room list service: joined chats, newest activity first. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
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
            result.controller().setFilter(
                RoomListEntriesDynamicFilterKind.All(
                    listOf(RoomListEntriesDynamicFilterKind.Joined, RoomListEntriesDynamicFilterKind.NonSpace),
                ),
            )
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

    /** Action loadMore: the next page, when the list scrolls near its end. */
    fun loadMore() {
        entries?.controller()?.addOnePage()
    }

    fun close() {
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
            listLoaded && rooms.isEmpty() -> Phase.EMPTY
            else -> Phase.LOADING
        }
    }

    private fun refreshSummaries() {
        val snapshot = rooms.toList()
        summaryJob?.cancel()
        summaryJob = scope.launch {
            _chats.value = snapshot.mapNotNull { summary(it) }
            updatePhase()
        }
    }

    private suspend fun summary(room: Room): ChatSummary? {
        val info = runCatching { room.roomInfo() }.getOrNull() ?: return null
        var lastMessage: String? = null
        var lastActivity: Long? = null
        when (val latest = room.latestEvent()) {
            is LatestEventValue.Remote -> {
                lastMessage = latest.content.previewText(undecryptableText, label)
                lastActivity = latest.timestamp.toLong()
            }
            is LatestEventValue.Local -> {
                lastMessage = latest.content.previewText(undecryptableText, label)
                lastActivity = latest.timestamp.toLong()
            }
            is LatestEventValue.RemoteInvite -> lastActivity = latest.timestamp.toLong()
            LatestEventValue.None -> Unit
        }
        return ChatSummary(
            roomId = info.id,
            name = info.displayName ?: info.id,
            avatarUrl = info.avatarUrl,
            lastMessage = lastMessage,
            lastActivity = lastActivity,
            unreadCount = info.numUnreadMessages.toInt(),
            isDirect = info.isDirect,
        )
    }

    private companion object {
        const val PAGE_SIZE = 50u
    }
}
