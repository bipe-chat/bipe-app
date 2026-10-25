// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.os.SystemClock
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.shared.BipeRowActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Spec chat-list, bipe-chat/bipe#592: the local timeline events of rooms without an SDK latest event, for the row date
 * (BipeRowActivity). One scanner per account session; it reads only what the SDK already stored on this phone. Call it
 * on the model's scope (main thread).
 */
class RowActivityScanner(
    private val client: Client,
    private val scope: CoroutineScope,
    private val onResults: () -> Unit,
) {
    private val events = mutableMapOf<String, List<BipeRowActivity.LocalEvent>>()
    private val scannedAt = mutableMapOf<String, Long>()
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null

    /** The local events read for the room, or null when they were not read yet. Asks for a read when needed. */
    fun localEvents(roomId: String): List<BipeRowActivity.LocalEvent>? {
        val cached = events[roomId]
        val stale = cached?.isEmpty() == true && (scannedAt[roomId]?.let { SystemClock.elapsedRealtime() - it > EMPTY_RETRY_MS } ?: true)
        if ((cached == null || stale) && roomId !in queue) {
            queue.addLast(roomId)
            startWorker()
        }
        return cached
    }

    /** The room changed in the room list: its newest events may be new. */
    fun invalidate(roomId: String) {
        events.remove(roomId)
        scannedAt.remove(roomId)
    }

    fun close() {
        worker?.cancel()
        queue.clear()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            var sinceUpdate = 0
            while (queue.isNotEmpty()) {
                val roomId = queue.removeFirst()
                val room = runCatching { client.getRoom(roomId) }.getOrNull()
                events[roomId] = room?.let { read(it) }.orEmpty()
                scannedAt[roomId] = SystemClock.elapsedRealtime()
                sinceUpdate += 1
                // Publish in batches: each publish summarizes every row again.
                if (queue.isEmpty() || sinceUpdate >= 8) {
                    sinceUpdate = 0
                    onResults()
                }
            }
        }
    }

    /**
     * The SDK sends the cached timeline items as the first update, before addListener returns (matrix-sdk-ffi
     * Timeline.add_listener). No pagination, no network.
     */
    private suspend fun read(room: Room): List<BipeRowActivity.LocalEvent> = try {
        room.timeline().use { timeline ->
            val items = mutableListOf<TimelineItem>()
            val handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    synchronized(items) { diff.forEach { items.applyDiff(it.toVectorDiff()) } }
                }
            })
            handle.cancel()
            synchronized(items) {
                items.mapNotNull { item ->
                    item.asEvent()?.let { event ->
                        // Message-like events, undecryptable ones included: the time of an encrypted event is not encrypted.
                        val kind = if (event.content is TimelineItemContent.MsgLike) BipeRowActivity.Kind.MESSAGE else BipeRowActivity.Kind.OTHER
                        BipeRowActivity.LocalEvent(event.timestamp.toLong(), kind)
                    }
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val EMPTY_RETRY_MS = 30_000L
    }
}
