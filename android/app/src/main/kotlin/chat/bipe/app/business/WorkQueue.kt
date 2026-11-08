// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.business

import android.content.Context
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.toVectorDiff
import chat.bipe.shared.api.BipeApiErrorCode
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeChatAssignment
import chat.bipe.shared.api.BipeSeatList
import chat.bipe.shared.api.businessChats
import chat.bipe.shared.api.seats
import chat.bipe.shared.api.updateBusinessChat
import chat.bipe.shared.company.BipeWorkNotes
import chat.bipe.shared.generated.BipeRegistries
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener

/**
 * The notes room IDs of work accounts, by user ID (bipe-shared registries/work-notes.json). The chat list, search and
 * forward leave the room out, like the drafts room.
 */
object WorkNotesRooms {
    private val ids = ConcurrentHashMap<String, String>()
    private val _revision = MutableStateFlow(0L)
    /** Goes up when a notes room ID becomes known, so the room lists hide it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun roomId(userId: String): String? = ids[userId]

    fun isNotesRoom(roomId: String, userId: String): Boolean = ids[userId] == roomId

    internal fun remember(userId: String, roomId: String) {
        if (ids.put(userId, roomId) != roomId) _revision.update { it + 1 }
    }

    /** Reads chat.bipe.work_notes_room of an account. A failed read keeps what is known. */
    suspend fun learn(session: AccountSession): String? {
        val json = runCatching { session.client.accountData(BipeRegistries.WORK_NOTES_ACCOUNT_DATA_TYPE) }.getOrNull()
        BipeWorkNotes.roomId(json)?.let { remember(session.userId, it) }
        return ids[session.userId]
    }
}

/**
 * The queue of a work account with two or more people (bipe ADR 0284 phase A, bipe-chat/bipe#1293): who has each
 * customer chat (specs work-chats and work-chat-bar), the notes of the notes room (spec work-notes) and the names on
 * replies (reply-by events). One per account for the life of the app. With one person [State.active] is false and no
 * screen changes.
 */
object WorkQueue {
    /** Snapshot state, so a screen that composed before the queue was made shows it once it is. Written on the main thread. */
    private val accounts = androidx.compose.runtime.mutableStateMapOf<String, WorkAccount>()

    /** The queue of [session], made on first use. A new session of the same account replaces the old one. */
    fun of(context: Context, session: AccountSession): WorkAccount {
        val known = accounts[session.userId]
        if (known != null && known.session === session) return known
        known?.close()
        return WorkAccount(context.applicationContext, session).also { accounts[session.userId] = it }
    }

    /** The queue of an account, when one was made. */
    fun account(userId: String): WorkAccount? = accounts[userId]
}

class WorkAccount internal constructor(private val context: Context, val session: AccountSession) {
    /** One note of spec work-notes. [seatId] null: the author is unknown, only the time shows. */
    data class Note(val eventId: String, val roomId: String, val body: String, val timestampMs: Long, val seatId: String? = null)

    /** State phase of spec work-chat-bar. */
    enum class Phase { IDLE, UPDATING, TAKEN, FAILED }

    /** State phase of spec work-notes. */
    enum class NotesPhase { LOADING, READY, SENDING, FAILED }

    data class State(
        val seats: BipeSeatList? = null,
        /** Room ID to its record. A customer chat with no record is open and in the pool. */
        val chats: Map<String, BipeChatAssignment> = emptyMap(),
        val chatsLoaded: Boolean = false,
        val phases: Map<String, Phase> = emptyMap(),
        /** Every note of the notes room, oldest first. */
        val notes: List<Note> = emptyList(),
        val notesPhase: NotesPhase = NotesPhase.LOADING,
        /** Event ID of a reply to a customer to the seat ID of a valid reply-by event. */
        val replyBy: Map<String, String> = emptyMap(),
    ) {
        /** Spec work-chats people: the queue shows only at 2 or more active seats, and only on a device with a person. */
        val active: Boolean get() = seats != null && seats.activeSeats.size >= 2 && seats.me.seatId != null
        val mySeat: String? get() = seats?.me?.seatId

        fun assignee(roomId: String): String? = chats[roomId]?.assigneeSeatId
        fun status(roomId: String): String = chats[roomId]?.status ?: BipeChatAssignment.OPEN
        fun phase(roomId: String): Phase = phases[roomId] ?: Phase.IDLE
        fun seatName(seatId: String?): String? = seatId?.let { id -> seats?.seats?.firstOrNull { it.id == id }?.name }
        fun notes(roomId: String): List<Note> = notes.filter { it.roomId == roomId }

        /** The seat name of a valid reply-by event for [eventId], inside the account only. */
        fun replyByName(eventId: String?): String? = if (!active || eventId == null) null else seatName(replyBy[eventId])
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val business = BusinessApi(session)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptionOwner = UUID.randomUUID().toString()
    private val prefs = context.getSharedPreferences("bipe.work_notes", Context.MODE_PRIVATE)
    private val pendingKey = "pending.${session.userId}"
    private val roomMutex = Mutex()
    private var timeline: Timeline? = null
    private var watchedRoomId: String? = null
    private var timelineHandle: TaskHandle? = null
    private var timelineJob: Job? = null
    private val items = mutableListOf<TimelineItem>()
    private var sendQueueHandle: TaskHandle? = null
    private var sendQueueJob: Job? = null
    /** Room ID to whether it is a customer chat (a DM that is not the notes room). */
    private val customerChats = ConcurrentHashMap<String, Boolean>()
    /** Events this device already wrote a reply-by event for. */
    private val repliedBy = ConcurrentHashMap.newKeySet<String>()

    /** Action load of spec work-chats: GET seats, then GET chats, at start and when the app comes to the front. */
    suspend fun refresh() {
        val seats = try {
            business.withToken { seats(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        _state.update { it.copy(seats = seats) }
        if (!_state.value.active) {
            stopWatching()
            return
        }
        loadChats()
        watchSendQueue()
        watchNotes()
    }

    private suspend fun loadChats() {
        val list = try {
            business.withToken { businessChats(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        _state.update { it.copy(chats = list.chats.associateBy { chat -> chat.roomId }, chatsLoaded = true) }
    }

    /** Action take of spec work-chat-bar. */
    fun take(roomId: String) {
        val mine = _state.value.mySeat ?: return
        update(roomId, mine, null)
    }

    /** Action give: the chat goes to [seatId]. */
    fun give(roomId: String, seatId: String) = update(roomId, seatId, null)

    /** Action release: the chat goes back to the pool. */
    fun release(roomId: String) = update(roomId, null, null)

    /** Action setStatus: the current assignee in both fields and the new status. */
    fun setStatus(roomId: String, status: String) = update(roomId, _state.value.assignee(roomId), status)

    private fun update(roomId: String, assignee: String?, status: String?) {
        val state = _state.value
        if (!state.active || state.phase(roomId) == Phase.UPDATING) return
        val expected = state.assignee(roomId)
        setPhase(roomId, Phase.UPDATING)
        scope.launch {
            try {
                val record = business.withToken { updateBusinessChat(roomId, assignee, expected, status, it) }
                _state.update { it.copy(chats = it.chats + (roomId to record)) }
                setPhase(roomId, Phase.IDLE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if ((error as? BipeApiException.Server)?.code == BipeApiErrorCode.CHAT_TAKEN) {
                    // Another person changed the record first: read the chats again; the bar shows who has it now.
                    loadChats()
                    setPhase(roomId, Phase.TAKEN)
                } else {
                    setPhase(roomId, Phase.FAILED)
                }
            }
        }
    }

    private fun setPhase(roomId: String, phase: Phase) {
        _state.update { it.copy(phases = it.phases + (roomId to phase)) }
    }

    // The notes room (registries/work-notes.json).

    /**
     * The notes room ID: from the account data, else a new private encrypted room with only the account's own user,
     * named in the account data. Offline or malformed account data never means "no room yet". A room made before a
     * failed account data write is used again on retry, also after a restart.
     */
    private suspend fun notesRoomId(): String = roomMutex.withLock {
        val json = session.client.accountData(BipeRegistries.WORK_NOTES_ACCOUNT_DATA_TYPE)
        val known = BipeWorkNotes.roomId(json)
        check(json == null || known != null) { "malformed notes room account data" }
        if (known != null) {
            WorkNotesRooms.remember(session.userId, known)
            return@withLock known
        }
        // Default chosen: no m.room.create type. CreateRoomParameters of matrix-sdk-ffi 26.08.11 has no creation type;
        // the registry asks for roomType only when the SDK can set it.
        val id = prefs.getString(pendingKey, null) ?: session.client.createRoom(CreateRoomParameters(
            name = null, isEncrypted = true, isDirect = false,
            visibility = RoomVisibility.Private, preset = RoomPreset.PRIVATE_CHAT, invite = null,
        )).also { created ->
            withContext(Dispatchers.IO) { check(prefs.edit().putString(pendingKey, created).commit()) }
        }
        WorkNotesRooms.remember(session.userId, id)
        session.client.setAccountData(BipeRegistries.WORK_NOTES_ACCOUNT_DATA_TYPE, BipeWorkNotes.accountDataContent(id))
        prefs.edit().remove(pendingKey).apply()
        runCatching { session.client.awaitRoomRemoteEcho(id) }
        id
    }

    /** Reads the notes room, when it exists, and follows its timeline. Each item goes through the two rules. */
    private suspend fun watchNotes() {
        val roomId = WorkNotesRooms.learn(session)
        if (roomId == null) {
            _state.update { it.copy(notesPhase = if (it.notesPhase == NotesPhase.LOADING) NotesPhase.READY else it.notesPhase) }
            return
        }
        if (watchedRoomId == roomId && timeline != null) return
        stopTimeline()
        val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return
        if (runCatching { room.membership() }.getOrNull() != Membership.JOINED) return
        val timeline = runCatching { room.timeline() }.getOrNull() ?: return
        watchedRoomId = roomId
        this.timeline = timeline
        session.roomSubscriptions.update(subscriptionOwner, listOf(roomId))
        val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
        timelineHandle = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                diffs.trySend(diff)
            }
        })
        timelineJob = scope.launch {
            for (batch in diffs) {
                batch.forEach { items.applyDiff(it.toVectorDiff()) }
                rebuild()
            }
        }
        _state.update { it.copy(notesPhase = if (it.notesPhase == NotesPhase.LOADING) NotesPhase.READY else it.notesPhase) }
        // Notes and reply-by events of the account: read back a few pages.
        scope.launch {
            repeat(10) {
                val hitStart = runCatching { timeline.paginateBackwards(50u) }.getOrNull() ?: return@launch
                if (hitStart) return@launch
            }
        }
    }

    private fun rebuild() {
        val joined = runCatching { session.client.rooms() }.getOrDefault(emptyList())
            .filter { runCatching { it.membership() == Membership.JOINED }.getOrDefault(false) }
            .map { it.id() }
            .toSet()
        val seatIds = _state.value.seats?.seats?.map { it.id }?.toSet().orEmpty()
        val notes = mutableListOf<Note>()
        val replyBy = mutableMapOf<String, String>()
        for (item in items) {
            val event = item.asEvent() ?: continue
            val eventId = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: continue
            val redacted = (event.content as? TimelineItemContent.MsgLike)?.content?.kind is MsgLikeKind.Redacted
            val json = runCatching { event.lazyProvider.debugInfo().originalJson }.getOrNull()
            // Reply-by notices are not notes: noteFromEvent hides every m.notice, so they never show in the sheet.
            when (val note = BipeWorkNotes.noteFromEventJson(json, event.sender, redacted, session.userId, joined, seatIds)) {
                is BipeWorkNotes.NoteResult.Show ->
                    notes += Note(eventId, note.note.roomId, note.note.body, event.timestamp.toLong(), note.note.seatId)
                is BipeWorkNotes.NoteResult.Hide -> {
                    val reply = BipeWorkNotes.replyByFromEventJson(json, event.sender, redacted, session.userId, seatIds)
                    if (reply is BipeWorkNotes.ReplyByResult.Show) replyBy[reply.replyBy.eventId] = reply.replyBy.seatId
                }
            }
        }
        _state.update { it.copy(notes = notes, replyBy = it.replyBy + replyBy) }
    }

    /** Action send of spec work-notes: makes the notes room on first use, then the note. Returns false on failure. */
    suspend fun sendNote(roomId: String, body: String): Boolean {
        val text = body.trim()
        if (text.isEmpty() || text.length > BipeRegistries.WORK_NOTE_BODY_MAX_LENGTH) return false
        _state.update { it.copy(notesPhase = NotesPhase.SENDING) }
        val sent = try {
            val notesRoom = notesRoomId()
            val room = session.client.getRoom(notesRoom) ?: error("no notes room")
            room.sendRaw("m.room.message", BipeWorkNotes.noteContent(text, roomId, _state.value.mySeat))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        _state.update { it.copy(notesPhase = if (sent) NotesPhase.READY else NotesPhase.FAILED) }
        if (sent) watchNotes()
        return sent
    }

    // Claim on reply and reply-by (spec work-chat-bar reply), for every kind of send through the SDK send queue.

    private suspend fun watchSendQueue() {
        if (sendQueueHandle != null) return
        val updates = Channel<Pair<String, RoomSendQueueUpdate>>(Channel.UNLIMITED)
        sendQueueHandle = runCatching {
            session.client.subscribeToSendQueueUpdates(object : SendQueueRoomUpdateListener {
                override fun onUpdate(roomId: String, update: RoomSendQueueUpdate) {
                    updates.trySend(roomId to update)
                }
            })
        }.getOrNull() ?: return
        sendQueueJob = scope.launch {
            for ((roomId, update) in updates) {
                when (update) {
                    is RoomSendQueueUpdate.NewLocalEvent -> claimOnReply(roomId)
                    is RoomSendQueueUpdate.SentEvent -> sendReplyBy(roomId, update.eventId)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun isCustomerChat(roomId: String): Boolean {
        if (WorkNotesRooms.isNotesRoom(roomId, session.userId)) return false
        customerChats[roomId]?.let { return it }
        val isDm = runCatching { session.client.getRoom(roomId)?.roomInfo()?.isDm }.getOrNull() ?: return false
        customerChats[roomId] = isDm
        return isDm
    }

    /** A send to a chat in the pool takes it first (ADR 0284 point 16). */
    private suspend fun claimOnReply(roomId: String) {
        val state = _state.value
        if (!state.active || !state.chatsLoaded || state.assignee(roomId) != null) return
        if (!isCustomerChat(roomId)) return
        take(roomId)
    }

    /** After a send to a customer: one reply-by event in the notes room (ADR 0284 point 19). */
    private suspend fun sendReplyBy(roomId: String, eventId: String) {
        val seat = _state.value.mySeat ?: return
        if (!_state.value.active || !isCustomerChat(roomId) || !repliedBy.add(eventId)) return
        _state.update { it.copy(replyBy = it.replyBy + (eventId to seat)) }
        runCatching {
            val room = session.client.getRoom(notesRoomId()) ?: error("no notes room")
            room.sendRaw("m.room.message", BipeWorkNotes.replyByContent(roomId, eventId, seat))
        }.onFailure { repliedBy.remove(eventId) }
        watchNotes()
    }

    private fun stopTimeline() {
        timelineHandle?.cancel()
        timelineHandle = null
        timelineJob?.cancel()
        timelineJob = null
        timeline = null
        watchedRoomId = null
        items.clear()
        session.releaseRoomSubscriptions(subscriptionOwner)
    }

    private fun stopWatching() {
        stopTimeline()
        sendQueueHandle?.cancel()
        sendQueueHandle = null
        sendQueueJob?.cancel()
        sendQueueJob = null
    }

    internal fun close() {
        stopWatching()
        scope.coroutineContext[Job]?.cancel()
    }
}
