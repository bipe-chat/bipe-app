// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.shared.BipeChatArchiveStore
import chat.bipe.shared.generated.BipeChatArchive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Membership

/** One instance per account. Only the m.lowpriority tag and local baselines change. */
class ChatArchiveModel(
    private val store: BipeChatArchiveStore,
    private val session: AccountSession,
    scope: CoroutineScope,
) {
    private val worker = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val _markers = MutableStateFlow<Map<String, Long>>(emptyMap())
    val markers = _markers.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed = _failed.asStateFlow()
    private var syncedRooms = emptySet<String>()
    private var latestChats = emptyList<ChatSummary>()
    private data class Action(val roomId: String, val archived: Boolean, val marker: Long?)
    private data class Echo(val id: UUID, val archived: Boolean)
    private val failedActions = mutableMapOf<String, Action>()
    private val followUps = mutableMapOf<String, Action>()
    private var storageFailed = false
    private val queuedRooms = mutableSetOf<String>()
    private val echoes = mutableMapOf<String, Echo>()
    private var tail: Job? = null
    private var closed = false

    init { reload() }

    fun set(chat: ChatSummary, archived: Boolean) {
        val marker = (chat.lastActivity ?: 0L).coerceAtLeast(0L).takeIf { it <= 9_007_199_254_740_991L }
        val action = Action(chat.roomId, archived, if (archived) marker else null)
        failedActions.remove(chat.roomId)
        if (chat.roomId in queuedRooms) followUps[chat.roomId] = action else enqueue(action)
    }

    fun reconcile(chats: List<ChatSummary>) {
        if (closed) return
        latestChats = chats
        if (_failed.value) return
        val next = _markers.value.toMutableMap()
        val synced = syncedRooms.toMutableSet()
        for (chat in chats) {
            if (chat.roomId in queuedRooms) continue
            val echo = echoes[chat.roomId]
            if (echo != null) {
                if (echo.archived != chat.isLowPriority) continue
                echoes.remove(chat.roomId)
            }
            val decision = BipeChatArchive.reconcile(next[chat.roomId], chat.roomId in synced, chat.isLowPriority,
                chat.lastActivity, chat.latestIncomingMs)
            val archived = decision.tagWrite
            if (archived != null) enqueue(Action(chat.roomId, archived, decision.marker))
            else {
                if (decision.marker == null) next.remove(chat.roomId) else next[chat.roomId] = decision.marker
                if (decision.synced) synced += chat.roomId else synced -= chat.roomId
            }
        }
        if (next == _markers.value && synced == syncedRooms) return
        try {
            store.save(next, synced)
            _markers.value = next
            syncedRooms = synced
        } catch (_: Exception) { storageFailed = true; _failed.value = true }
    }

    fun retry(chats: List<ChatSummary>) {
        reload()
        if (storageFailed) return
        val actions = failedActions.values.toList()
        failedActions.clear()
        _failed.value = false
        actions.forEach(::enqueue)
        reconcile(chats)
    }

    private fun enqueue(action: Action) {
        if (closed || !queuedRooms.add(action.roomId)) return
        val previous = tail
        tail = worker.launch(Dispatchers.Main) {
            previous?.join()
            try {
                store.loadState()
                check(!action.archived || action.marker != null)
                val room = session.client.getRoom(action.roomId) ?: error("archive room unavailable")
                val info = room.roomInfo()
                check(info.membership == Membership.JOINED && !info.isSpace)
                room.setIsLowPriority(action.archived, null)
                val current = store.loadState()
                val next = current.markers.toMutableMap()
                val synced = current.syncedRooms.toMutableSet()
                if (action.archived) {
                    next[action.roomId] = checkNotNull(action.marker)
                    synced += action.roomId
                } else {
                    next.remove(action.roomId)
                    synced -= action.roomId
                }
                store.save(next, synced)
                _markers.value = next
                syncedRooms = synced
                storageFailed = false
                failedActions.remove(action.roomId)
                val echo = Echo(UUID.randomUUID(), action.archived)
                echoes[action.roomId] = echo
                worker.launch(Dispatchers.Main) {
                    delay(BipeChatArchive.echoGraceMs)
                    if (echoes[action.roomId]?.id == echo.id) {
                        echoes.remove(action.roomId)
                        reconcile(latestChats)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failedActions[action.roomId] = action }
            finally { queuedRooms.remove(action.roomId) }
            followUps.remove(action.roomId)?.let(::enqueue)
            _failed.value = storageFailed || failedActions.isNotEmpty()
            reconcile(latestChats)
        }
    }

    private fun reload() {
        try {
            val state = store.loadState()
            _markers.value = state.markers
            syncedRooms = state.syncedRooms
            storageFailed = false
        } catch (_: Exception) { storageFailed = true }
        _failed.value = storageFailed || failedActions.isNotEmpty()
    }

    fun close() { closed = true; worker.cancel() }
}
