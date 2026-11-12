// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType

sealed interface SavedComposerDraft {
    data class Text(val body: String, val replyTo: String? = null) : SavedComposerDraft
    /** Preserve edit/attachment drafts until the corresponding Windows composer exists. */
    data object Unsupported : SavedComposerDraft
}

interface ComposerDraftStore {
    suspend fun load(roomId: String): SavedComposerDraft
    suspend fun save(roomId: String, draft: SavedComposerDraft.Text)
}

/** Uses the pinned Rust SDK's encrypted state store, not a second file format or an assistant-draft parser. */
class SdkComposerDraftStore(private val client: Client) : ComposerDraftStore {
    override suspend fun load(roomId: String): SavedComposerDraft {
        val room = client.getRoom(roomId) ?: throw StorageFailure(StorageCode.MISSING_STORE)
        try {
            val saved = room.loadComposerDraft(null) ?: return SavedComposerDraft.Text("")
            try {
                if (saved.attachments.isNotEmpty() || saved.htmlText != null) return SavedComposerDraft.Unsupported
                return when (val type = saved.draftType) {
                    ComposerDraftType.NewMessage -> SavedComposerDraft.Text(saved.plainText)
                    is ComposerDraftType.Reply -> SavedComposerDraft.Text(saved.plainText, type.eventId)
                    is ComposerDraftType.Edit -> SavedComposerDraft.Unsupported
                }
            } finally { saved.destroy() }
        } finally { room.destroy() }
    }

    override suspend fun save(roomId: String, draft: SavedComposerDraft.Text) {
        val room = client.getRoom(roomId) ?: throw StorageFailure(StorageCode.MISSING_STORE)
        try {
            // A reply with no text still carries intent; cancelling the reply is a separate persisted change.
            if (draft.body.isBlank() && draft.replyTo == null) room.clearComposerDraft(null)
            else {
                val value = ComposerDraft(draft.body, null, draft.replyTo?.let { ComposerDraftType.Reply(it) }
                    ?: ComposerDraftType.NewMessage, emptyList())
                try { room.saveComposerDraft(value, null) } finally { value.destroy() }
            }
        } finally { room.destroy() }
    }
}

enum class DraftPersistence { SAVED, PENDING, FAILED }
sealed interface ComposerState {
    data object Loading : ComposerState
    data object LoadFailed : ComposerState
    data object Unsupported : ComposerState
    data class Editing(val text: String, val revision: Long, val persistence: DraftPersistence, val replyTo: String? = null) : ComposerState
}
internal class DraftSubmission(val text: String, val revision: Long, val composer: RoomComposer, val replyTo: String?) {
    override fun toString(): String = "DraftSubmission(<redacted>)"
}

/** Immutable account/room binding. No caller supplies another account, path or native Room. */
class RoomComposer internal constructor(private val owner: AccountDrafts, internal val roomId: String) {
    internal val mutableState = MutableStateFlow<ComposerState>(ComposerState.Loading)
    val state = mutableState.asStateFlow()
    fun edit(text: String) { owner.edit(this, text) }
    fun retry() { owner.retry(this) }
    internal fun replyTo(eventId: String) { if (eventId.isNotBlank()) owner.replyTo(this, eventId) }
    fun cancelReply() { owner.replyTo(this, null) }
    internal fun submission(): DraftSubmission? = (mutableState.value as? ComposerState.Editing)
        ?.takeIf { it.text.isNotBlank() }?.let { DraftSubmission(it.text, it.revision, this, it.replyTo) }
    internal fun accepted(submission: DraftSubmission) { owner.accepted(this, submission) }
}

/**
 * One coalescing queue per account. Accepted writes drain before the native client/key/lock are released.
 * The worker deliberately outlives the account's cancelled UI tasks; it only touches this captured store.
 */
class AccountDrafts(private val lifetime: AccountLifetime, private val store: ComposerDraftStore) : AccountResource {
    private enum class Phase { ACTIVE, CLOSING, BLOCKED, CLOSED }
    private sealed interface Work {
        val room: RoomComposer
        data class Load(override val room: RoomComposer) : Work
        data class Save(override val room: RoomComposer, val value: ComposerState.Editing) : Work
    }
    private val monitor = Any()
    private val closing = Mutex()
    private var phase = Phase.ACTIVE
    private val rooms = mutableMapOf<String, RoomComposer>()
    private val pending = linkedMapOf<String, Work>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePreviews = MutableStateFlow<Map<String, String?>>(emptyMap())
    val previews = mutablePreviews.asStateFlow()
    private val worker = scope.launch {
        for (signal in signals) while (true) {
            val work = synchronized(monitor) {
                pending.keys.firstOrNull()?.let { pending.remove(it) }
            } ?: break
            perform(work)
        }
    }

    fun room(id: String): RoomComposer {
        var result: RoomComposer? = null
        lifetime.publish(lifetime.epoch) { synchronized(monitor) {
            if (phase == Phase.ACTIVE) result = rooms.getOrPut(id) { RoomComposer(this, id).also { enqueue(Work.Load(it)) } }
        } }
        return result ?: throw StorageFailure(StorageCode.STALE_OWNER)
    }

    internal fun edit(room: RoomComposer, text: String) = change {
        val current = room.mutableState.value as? ComposerState.Editing ?: return@change
        update(room, current.copy(text = text, revision = current.revision + 1, persistence = DraftPersistence.PENDING))
    }

    internal fun replyTo(room: RoomComposer, eventId: String?) = change {
        val current = room.mutableState.value as? ComposerState.Editing ?: return@change
        if (current.replyTo != eventId) update(room, current.copy(replyTo = eventId,
            revision = current.revision + 1, persistence = DraftPersistence.PENDING))
    }

    /** Called only after owned SDK queue acceptance; room/account teardown joins that task before this writer. */
    internal fun accepted(room: RoomComposer, submitted: DraftSubmission) = synchronized(monitor) {
        if (phase != Phase.ACTIVE || submitted.composer !== room) return@synchronized
        val current = room.mutableState.value as? ComposerState.Editing ?: return@synchronized
        // Body equality alone loses an edit away and back to the same text while the SDK send is in flight.
        if (current.revision == submitted.revision) update(room, ComposerState.Editing("", current.revision + 1, DraftPersistence.PENDING))
    }

    internal fun retry(room: RoomComposer) = change {
        when (val current = room.mutableState.value) {
            ComposerState.LoadFailed -> { room.mutableState.value = ComposerState.Loading; enqueue(Work.Load(room)) }
            is ComposerState.Editing -> if (current.persistence == DraftPersistence.FAILED) update(room, current.copy(persistence = DraftPersistence.PENDING))
            else -> Unit
        }
    }

    // One lock order throughout: lifetime, then draft monitor. No native I/O inside either lock.
    private fun change(action: () -> Unit) = lifetime.publish(lifetime.epoch) {
        synchronized(monitor) { if (phase == Phase.ACTIVE) action() }
    }

    private fun update(room: RoomComposer, value: ComposerState.Editing) {
        room.mutableState.value = value
        mutablePreviews.value = mutablePreviews.value + (room.roomId to value.text.takeIf { it.isNotBlank() })
        enqueue(Work.Save(room, value))
    }

    private fun enqueue(work: Work) { pending[work.room.roomId] = work; check(signals.trySend(Unit).isSuccess) }
    private suspend fun perform(work: Work) {
        val result = try {
            when (work) {
                is Work.Load -> when (val saved = store.load(work.room.roomId)) {
                    is SavedComposerDraft.Text -> ComposerState.Editing(saved.body, 0, DraftPersistence.SAVED, saved.replyTo)
                    SavedComposerDraft.Unsupported -> ComposerState.Unsupported
                }
                is Work.Save -> {
                    store.save(work.room.roomId, SavedComposerDraft.Text(work.value.text, work.value.replyTo))
                    work.value.copy(persistence = DraftPersistence.SAVED)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            when (work) {
                is Work.Load -> ComposerState.LoadFailed
                is Work.Save -> work.value.copy(persistence = DraftPersistence.FAILED)
            }
        }
        synchronized(monitor) {
            // Internal durability state still settles during teardown; it never publishes into another owner.
            val current = work.room.mutableState.value
            if (work is Work.Load || (current is ComposerState.Editing && work is Work.Save && current.revision == work.value.revision)) {
                work.room.mutableState.value = result
                if (result is ComposerState.Editing) mutablePreviews.value = mutablePreviews.value +
                    (work.room.roomId to result.text.takeIf { it.isNotBlank() })
            }
        }
    }

    override suspend fun close() = withContext(NonCancellable) { closing.withLock {
        synchronized(monitor) {
            if (phase == Phase.CLOSED) return@withLock
            phase = Phase.CLOSING
            signals.close()
        }
        try {
            withTimeout(10_000) { worker.join() }
            if (worker.isCancelled) throw StorageFailure(StorageCode.IO_FAILURE)
            // A failed write keeps the client/key/lock alive. Retry cleanup retries only the unsaved latest texts.
            val dirty = synchronized(monitor) { rooms.values.mapNotNull { room ->
                (room.mutableState.value as? ComposerState.Editing)?.takeIf { it.persistence != DraftPersistence.SAVED }
                    ?.let { Work.Save(room, it) }
            } }
            withTimeout(10_000) { dirty.forEach { perform(it) } }
            if (synchronized(monitor) { rooms.values.any { (it.mutableState.value as? ComposerState.Editing)?.persistence == DraftPersistence.FAILED } }) {
                throw StorageFailure(StorageCode.IO_FAILURE)
            }
            synchronized(monitor) { phase = Phase.CLOSED }
            scope.cancel()
        } catch (error: Exception) {
            synchronized(monitor) { phase = Phase.BLOCKED }
            throw error
        }
    } }
}
