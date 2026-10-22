// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.TaskHandle

/**
 * One subscription to the room list service with its own filter (RoomList.entriesWithDynamicAdapters), for the lists
 * next to chat-list: requests, group invites, search results.
 */
class FilteredRoomList(private val scope: CoroutineScope, private val onChange: suspend (List<Room>) -> Unit) {
    private val rooms = mutableListOf<Room>()
    private var result: RoomListEntriesWithDynamicAdaptersResult? = null
    private var handle: TaskHandle? = null
    private var job: Job? = null

    /** Subscribes once, then sets the filter; a later call only changes the filter. */
    suspend fun start(session: AccountSession, filter: RoomListEntriesDynamicFilterKind) {
        result?.let {
            it.controller().setFilter(filter)
            return
        }
        val roomList = runCatching { session.syncService.roomListService().allRooms() }.getOrNull() ?: return
        val updates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
        val subscription = roomList.entriesWithDynamicAdapters(
            PAGE_SIZE,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    updates.trySend(roomEntriesUpdate)
                }
            },
        )
        subscription.controller().setFilter(filter)
        handle = subscription.entriesStream()
        result = subscription
        job = scope.launch {
            for (batch in updates) {
                batch.forEach { rooms.applyDiff(it.toVectorDiff()) }
                onChange(rooms.toList())
            }
        }
    }

    fun loadMore() { result?.controller()?.addOnePage() }

    fun close() {
        handle?.cancel()
        job?.cancel()
    }

    private companion object {
        const val PAGE_SIZE = 50u
    }
}
