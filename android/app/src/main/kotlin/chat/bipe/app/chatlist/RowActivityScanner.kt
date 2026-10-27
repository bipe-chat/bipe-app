// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.os.SystemClock
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.shared.BipeRowActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * Spec chat-list, bipe-chat/bipe#592: the local timeline events of every row, for the row date and text
 * (BipeRowActivity). One scanner per account session; it reads only what the SDK already stored on this phone. Call it
 * on the model's scope (main thread).
 */
class RowActivityScanner(
    private val client: Client,
    private val scope: CoroutineScope,
    /** The row text of an event (content, isOwn, sender name, roomId, isDm), null when the row shows no text for it. */
    private val preview: (TimelineItemContent, Boolean, String, String, Boolean) -> String?,
    private val onResults: () -> Unit,
) {
    private val events = mutableMapOf<String, List<BipeRowActivity.LocalEvent>>()
    private val scannedAt = mutableMapOf<String, Long>()
    /** Rooms whose last read held an undecryptable message: its key can arrive later. */
    private val undecryptable = mutableSetOf<String>()
    private val undecryptableReads = mutableMapOf<String, Int>()
    /** A read found an undecryptable message in this run (spec encryption-status seesUndecryptable). */
    var sawUndecryptable = false
        private set
    private var retryJob: Job? = null
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null

    /** The local events read for the room, or null when they were not read yet. Asks for a read when needed. */
    fun localEvents(roomId: String): List<BipeRowActivity.LocalEvent>? {
        val cached = events[roomId]
        val retry = cached?.isEmpty() == true || roomId in undecryptable
        val stale = retry && (scannedAt[roomId]?.let { SystemClock.elapsedRealtime() - it > EMPTY_RETRY_MS } ?: true)
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
        undecryptableReads.remove(roomId)
    }

    fun close() {
        worker?.cancel()
        retryJob?.cancel()
        queue.clear()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            var sinceUpdate = 0
            while (queue.isNotEmpty()) {
                val roomId = queue.removeFirst()
                val room = runCatching { client.getRoom(roomId) }.getOrNull()
                val found = room?.let { read(it) } ?: Read(emptyList(), false)
                events[roomId] = found.events
                val reads = if (found.undecryptable) (undecryptableReads[roomId] ?: 0) + 1 else 0
                undecryptableReads[roomId] = reads
                if (found.undecryptable) sawUndecryptable = true
                if (found.undecryptable && reads < MAX_UNDECRYPTABLE_READS) undecryptable += roomId else undecryptable -= roomId
                scannedAt[roomId] = SystemClock.elapsedRealtime()
                sinceUpdate += 1
                // Publish in batches: each publish summarizes every row again.
                if (queue.isEmpty() || sinceUpdate >= 8) {
                    sinceUpdate = 0
                    onResults()
                }
            }
            scheduleRetry()
        }
    }

    /** A room key can arrive after the read without any room list change: ask for the rows again once the retry is due. */
    private fun scheduleRetry() {
        if (undecryptable.isEmpty() || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(EMPTY_RETRY_MS + 1_000)
            onResults()
        }
    }

    private class Read(val events: List<BipeRowActivity.LocalEvent>, val undecryptable: Boolean)

    /**
     * The SDK sends the cached timeline items as the first update, before addListener returns (matrix-sdk-ffi
     * Timeline.add_listener). No pagination, no network.
     */
    private suspend fun read(room: Room): Read = try {
        val isDm = runCatching { room.roomInfo().isDm }.getOrDefault(false)
        room.timeline().use { timeline ->
            val items = mutableListOf<TimelineItem>()
            val handle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    synchronized(items) { diff.forEach { items.applyDiff(it.toVectorDiff()) } }
                }
            })
            handle.cancel()
            synchronized(items) {
                val events = items.mapNotNull { it.asEvent() }
                val local = events.map { event ->
                    val text = preview(event.content, event.isOwn, event.senderProfile.displayName(event.sender), room.id(), isDm)
                    // Message-like events, undecryptable ones included: the time of an encrypted event is not encrypted. A
                    // call is a message too: the SDK takes it as a latest event, and the row shows it (spec incoming-call). A
                    // join, leave or room creation with its line counts as a message: the row shows it when it is the newest.
                    val kind = when (event.content) {
                        is TimelineItemContent.MsgLike, is TimelineItemContent.RtcNotification, is TimelineItemContent.CallInvite ->
                            BipeRowActivity.Kind.MESSAGE
                        is TimelineItemContent.RoomMembership, is TimelineItemContent.State ->
                            if (text == null) BipeRowActivity.Kind.OTHER else BipeRowActivity.Kind.MESSAGE
                        else -> BipeRowActivity.Kind.OTHER
                    }
                    BipeRowActivity.LocalEvent(event.timestamp.toLong(), kind, text, event.isOwn)
                }
                Read(local, events.any { (it.content as? TimelineItemContent.MsgLike)?.content?.kind is MsgLikeKind.UnableToDecrypt })
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Read(emptyList(), false)
    }

    private companion object {
        const val EMPTY_RETRY_MS = 30_000L
        /** An undecryptable message whose key has not come after this many reads stays as it is until the room changes. */
        const val MAX_UNDECRYPTABLE_READS = 10
    }
}
