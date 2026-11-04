// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.os.SystemClock
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import chat.bipe.shared.BipeRowActivity
import chat.bipe.shared.generated.BipeHomeScore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/** Spec chat-list, Mural inputs (ADR 0246): what a read of the local timeline found for one room. */
class LocalMessages(
    /** origin_server_ts of each message-like event, from anyone, undecryptable ones included. */
    val times: List<Long>,
    /** The newest message-like event has another sender. False when the room has no local message. */
    val lastFromOther: Boolean,
    /** The previews of the newest messages, oldest first, for the Mural tiles. */
    val recent: List<String>,
)

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
    /** The last read of each room. [invalidate] keeps it: a tile keeps its text while a changed room is read again. */
    private val messages = mutableMapOf<String, LocalMessages>()
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

    /**
     * Spec read-receipts, ack send: a read found the newest event from the other person in a DM (room, event ID,
     * sender). Each read follows a room list change, so a DM gets at most one ack per batch.
     */
    var onDmIncoming: ((Room, String, String) -> Unit)? = null

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

    /** The messages of the last read of the room, or null when no read finished yet. Asks for no read: see [localEvents]. */
    fun localMessages(roomId: String): LocalMessages? = messages[roomId]

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
                found.dmIncoming?.let { (eventId, sender) -> room?.let { onDmIncoming?.invoke(it, eventId, sender) } }
                events[roomId] = found.events
                found.messages?.let { messages[roomId] = it }
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

    private class Read(
        val events: List<BipeRowActivity.LocalEvent>,
        val undecryptable: Boolean,
        /** DM only: the newest event from the other person (event ID, sender). */
        val dmIncoming: Pair<String, String>? = null,
        /** null when the read failed: the room keeps the messages of its last read. */
        val messages: LocalMessages? = null,
    )

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
                    val kind = when {
                        event.content.isMessageLike() -> BipeRowActivity.Kind.MESSAGE
                        event.content is TimelineItemContent.RoomMembership || event.content is TimelineItemContent.State ->
                            if (text == null) BipeRowActivity.Kind.OTHER else BipeRowActivity.Kind.MESSAGE
                        else -> BipeRowActivity.Kind.OTHER
                    }
                    BipeRowActivity.LocalEvent(event.timestamp.toLong(), kind, text, event.isOwn)
                }
                // Spec read-receipts: the newest event this device got from the other person in a DM.
                val dmIncoming = if (!isDm) null else events.lastOrNull { !it.isOwn }?.let { newest ->
                    (newest.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId?.let { it to newest.sender }
                }
                // Spec chat-list, Mural inputs: a join or a leave is no message from anyone, so it never counts as unanswered.
                val messageLike = events.filter { it.content.isMessageLike() }
                val found = LocalMessages(
                    times = messageLike.map { it.timestamp.toLong() },
                    lastFromOther = messageLike.lastOrNull()?.isOwn == false,
                    recent = local.filter { it.kind == BipeRowActivity.Kind.MESSAGE }.mapNotNull { it.previewText }.takeLast(BipeHomeScore.FIRST_TILE_MESSAGES),
                )
                Read(local, events.any { (it.content as? TimelineItemContent.MsgLike)?.content?.kind is MsgLikeKind.UnableToDecrypt }, dmIncoming, found)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Read(emptyList(), false)
    }

    private fun TimelineItemContent.isMessageLike(): Boolean =
        this is TimelineItemContent.MsgLike || this is TimelineItemContent.RtcNotification || this is TimelineItemContent.CallInvite

    private companion object {
        const val EMPTY_RETRY_MS = 30_000L
        /** An undecryptable message whose key has not come after this many reads stays as it is until the room changes. */
        const val MAX_UNDECRYPTABLE_READS = 10
    }
}
