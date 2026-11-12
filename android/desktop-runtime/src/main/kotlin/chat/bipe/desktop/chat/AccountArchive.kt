// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.shared.generated.BipeChatArchive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Membership

data class ArchiveIntent(val id: String, val room: String, val archived: Boolean, val baseline: Long)
sealed interface ArchiveOperation {
    val intent: ArchiveIntent
    data class Queued(override val intent: ArchiveIntent) : ArchiveOperation
    data class Sending(override val intent: ArchiveIntent) : ArchiveOperation
    data class Failed(override val intent: ArchiveIntent) : ArchiveOperation
    data class Unsaved(override val intent: ArchiveIntent) : ArchiveOperation
}
enum class ArchivePersistence { CLEAN, FAILED }
sealed interface ArchivePhase {
    data object Loading : ArchivePhase
    data object Unavailable : ArchivePhase
    data class Ready(val markers: Map<String, Long>, val operations: Map<String, ArchiveOperation>,
                     val persistence: ArchivePersistence = ArchivePersistence.CLEAN,
                     val confirmed: Map<String, Boolean> = emptyMap()) : ArchivePhase {
        val failed get() = persistence == ArchivePersistence.FAILED || operations.values.any { it is ArchiveOperation.Failed || it is ArchiveOperation.Unsaved }
        fun effective(row: ChatRow): ChatRow {
            val archived = confirmed[row.id] ?: (row.id in markers || row.archived)
            return row.copy(archived = archived, favourite = row.favourite && !archived && row.id !in confirmed)
        }
    }
}
internal interface ArchiveJournal { fun read(): String?; fun write(value: String) }
internal fun interface ArchiveBackend { suspend fun set(room: String, archived: Boolean) }

/** Matrix owns the tag. Protected baselines and queued intents never contain message bodies or change membership. */
class AccountArchive private constructor(
    private val group: AccountTaskGroup, private val backend: ArchiveBackend, private val journal: ArchiveJournal,
    private val clock: () -> Long, private val monotonic: () -> Long,
) {
    private data class Marker(val time: Long, val synced: Boolean)
    private data class Confirmation(val archived: Boolean, val expires: Long)
    private data class Record(val markers: Map<String, Marker> = emptyMap(), val pending: Map<String, ArchiveIntent> = emptyMap(),
                              val echoes: Map<String, Confirmation> = emptyMap())
    private var record = Record()
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val deadlines = mutableMapOf<String, Long>()
    private val admissions = ConcurrentHashMap<String, String>()
    private var latestRows = emptyList<ChatRow>()
    private val mutablePhase = MutableStateFlow<ArchivePhase>(ArchivePhase.Loading)
    val phase = mutablePhase.asStateFlow()

    fun set(row: ChatRow, archived: Boolean) {
        if (row.id.isBlank()) return
        val baseline = maxOf(row.timestamp ?: 0, row.latestIncoming ?: 0).coerceAtLeast(0)
        if (baseline > MAX_TIME) return
        val intent = ArchiveIntent(UUID.randomUUID().toString(), row.id, archived, baseline)
        var admitted = false
        group.publish {
            if (mutablePhase.value is ArchivePhase.Ready) { admissions[row.id] = intent.id; admitted = true }
        }
        if (admitted) group.launch { lock.withLock {
            if (admissions[row.id] != intent.id) return@withLock
            protect(intent)
        } }
    }

    private fun ready() = mutablePhase.value as? ArchivePhase.Ready
    private fun publish(operations: Map<String, ArchiveOperation> = ready()?.operations.orEmpty(),
                        persistence: ArchivePersistence = ArchivePersistence.CLEAN) = group.publish {
        mutablePhase.value = ArchivePhase.Ready(record.markers.mapValues { it.value.time }, operations, persistence, record.echoes.mapValues { it.value.archived })
    }
    private fun protect(intent: ArchiveIntent) {
        try {
            val next = record.copy(pending = record.pending + (intent.room to intent))
            persist(next); record = next
            publish(ready()?.operations.orEmpty() + (intent.room to ArchiveOperation.Queued(intent)))
            wake.trySend(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(ready()?.operations.orEmpty() + (intent.room to ArchiveOperation.Unsaved(intent)), ArchivePersistence.FAILED) }
    }

    fun retry() = group.launch { lock.withLock {
        try {
            if (mutablePhase.value == ArchivePhase.Unavailable) load()
            val state = ready() ?: return@withLock
            val intents = state.operations.values.filterNot { it is ArchiveOperation.Sending }.associate { it.intent.room to it.intent }
            val next = record.copy(pending = record.pending + intents)
            persist(next); record = next
            publish(state.operations + intents.mapValues { ArchiveOperation.Queued(it.value) })
            reconcileLocked(latestRows)
            wake.trySend(Unit)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ready()?.let { publish(it.operations, ArchivePersistence.FAILED) } }
    } }

    /** Called with immutable SDK summaries, including the separate low-priority stream and saved room IDs. */
    suspend fun reconcile(rows: List<ChatRow>) = lock.withLock { latestRows = rows.toList(); reconcileLocked(rows) }

    private fun reconcileLocked(rows: List<ChatRow>) {
        val state = ready() ?: return
        if (state.persistence == ArchivePersistence.FAILED) return
        var next = record
        val operations = state.operations.toMutableMap()
        for (row in rows) {
            if (row.id in operations || row.id in next.pending) continue
            val echo = next.echoes[row.id]
            var serverArchived = row.archived
            if (echo != null) {
                if (echo.archived != row.archived && monotonic() < (deadlines[row.id] ?: 0)) serverArchived = echo.archived
                else {
                    deadlines.remove(row.id)
                    next = next.copy(echoes = next.echoes - row.id)
                }
            }
            val marker = next.markers[row.id]
            val decision = BipeChatArchive.reconcile(marker?.time, marker?.synced ?: false, serverArchived, row.timestamp, row.latestIncoming)
            if (decision.tagWrite != null) {
                val intent = ArchiveIntent(UUID.randomUUID().toString(), row.id, decision.tagWrite, decision.marker ?: 0)
                next = next.copy(pending = next.pending + (row.id to intent))
                operations[row.id] = ArchiveOperation.Queued(intent)
            } else {
                val desired = decision.marker?.let { Marker(it, decision.synced) }
                if (marker != desired) next = next.copy(markers = if (desired == null) next.markers - row.id else next.markers + (row.id to desired))
            }
        }
        if (next == record) return
        try { persist(next); record = next; publish(operations); wake.trySend(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(operations.mapValues { (room, operation) ->
            if (operation is ArchiveOperation.Queued && record.pending[room]?.id != operation.intent.id) ArchiveOperation.Unsaved(operation.intent) else operation
        }, ArchivePersistence.FAILED) }
    }

    private suspend fun work() {
        for (signal in wake) while (true) {
            val intent = lock.withLock {
                val state = ready() ?: return@withLock null
                val next = state.operations.values.filterIsInstance<ArchiveOperation.Queued>()
                    .firstOrNull { record.pending[it.intent.room]?.id == it.intent.id }?.intent ?: return@withLock null
                publish(state.operations + (next.room to ArchiveOperation.Sending(next)), state.persistence)
                next
            } ?: break
            try {
                group.requireCurrent(); backend.set(intent.room, intent.archived); group.requireCurrent()
                lock.withLock {
                    val confirmation = Confirmation(intent.archived, clock() + BipeChatArchive.echoGraceMs)
                    val next = record.copy(
                        markers = if (intent.archived) record.markers + (intent.room to Marker(intent.baseline, true)) else record.markers - intent.room,
                        pending = if (record.pending[intent.room]?.id == intent.id) record.pending - intent.room else record.pending,
                        echoes = record.echoes + (intent.room to confirmation),
                    )
                    persist(next); record = next
                    deadlines[intent.room] = monotonic() + BipeChatArchive.echoGraceMs
                    val operations = ready()?.operations.orEmpty()
                    publish(if (operations[intent.room]?.intent?.id == intent.id) operations - intent.room else operations,
                        ready()?.persistence ?: ArchivePersistence.CLEAN)
                    reconcileLocked(latestRows)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { lock.withLock {
                val operations = ready()?.operations.orEmpty()
                if (operations[intent.room]?.intent?.id == intent.id) publish(operations + (intent.room to ArchiveOperation.Failed(intent)),
                    ready()?.persistence ?: ArchivePersistence.CLEAN)
            } }
        }
    }

    private fun load() {
        record = journal.read()?.let(::decode) ?: Record()
        deadlines.clear()
        record.echoes.forEach { (room, echo) -> deadlines[room] = monotonic() + (echo.expires - clock()).coerceIn(0, BipeChatArchive.echoGraceMs) }
        publish(record.pending.mapValues { ArchiveOperation.Failed(it.value) })
    }
    private fun persist(value: Record) {
        group.requireCurrent()
        journal.read()?.let(::decode) // A failed/corrupt read can never silently replace protected metadata.
        journal.write(buildJsonObject {
            put("version", 1)
            put("markers", buildJsonObject { value.markers.forEach { (room, marker) -> put(room, buildJsonObject { put("time", marker.time); put("synced", marker.synced) }) } })
            put("pending", JsonArray(value.pending.values.map { intent -> buildJsonObject {
                put("id", intent.id); put("room", intent.room); put("archived", intent.archived); put("baseline", intent.baseline)
            } }))
            put("echoes", buildJsonObject { value.echoes.forEach { (room, echo) -> put(room, buildJsonObject { put("archived", echo.archived); put("expires", echo.expires) }) } })
        }.toString())
    }

    companion object {
        private const val MAX_TIME = 9_007_199_254_740_991L
        private fun time(value: JsonElement): Long = value.jsonPrimitive.long.also { check(it in 0..MAX_TIME) }
        private fun text(value: JsonElement): String = value.jsonPrimitive.let { check(it.isString && it.content.isNotBlank()); it.content }
        private fun decode(raw: String): Record {
            val json = Json.parseToJsonElement(raw).jsonObject
            check(json.getValue("version").jsonPrimitive.int == 1)
            val markers = json.getValue("markers").jsonObject.mapValues { (room, value) ->
                check(room.isNotBlank()); val m = value.jsonObject; Marker(time(m.getValue("time")), m.getValue("synced").jsonPrimitive.boolean)
            }
            val pending = json.getValue("pending").jsonArray.map { value -> val p = value.jsonObject
                ArchiveIntent(text(p.getValue("id")), text(p.getValue("room")), p.getValue("archived").jsonPrimitive.boolean, time(p.getValue("baseline")))
            }.also { list -> check(list.map { it.room }.distinct().size == list.size) }.associateBy { it.room }
            val echoes = json["echoes"]?.jsonObject?.mapValues { (room, value) ->
                check(room.isNotBlank()); val e = value.jsonObject; Confirmation(e.getValue("archived").jsonPrimitive.boolean, time(e.getValue("expires")))
            }.orEmpty()
            return Record(markers, pending, echoes)
        }
        suspend fun open(runtime: MatrixAccountRuntime): AccountArchive = create(runtime.lifetime, ArchiveBackend { id, archived ->
            runtime.withRoomTagWrite {
                val room = runtime.client.getRoom(id) ?: error("Archive room unavailable")
                try {
                    val info = room.roomInfo()
                    try { check(info.membership == Membership.JOINED && !info.isSpace) } finally { info.destroy() }
                    room.setIsLowPriority(archived, null)
                } finally { room.destroy() }
            }
        }, object : ArchiveJournal {
            override fun read() = runtime.lifetime.lease.readArchive()
            override fun write(value: String) = runtime.lifetime.lease.writeArchive(value)
        }, poll = true)

        internal suspend fun create(owner: AccountLifetime, backend: ArchiveBackend, journal: ArchiveJournal,
                                    clock: () -> Long = System::currentTimeMillis, monotonic: () -> Long = { System.nanoTime() / 1_000_000 },
                                    poll: Boolean = false): AccountArchive {
            val group = AccountTaskGroup.create(owner)
            val model = AccountArchive(group, backend, journal, clock, monotonic)
            try { group.async { model.lock.withLock { model.load() } }.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { model.mutablePhase.value = ArchivePhase.Unavailable } }
            group.launch { model.work() }
            if (poll) group.launch {
                if ((model.phase.value as? ArchivePhase.Ready)?.operations?.isNotEmpty() == true) model.retry().join()
                while (currentCoroutineContext().isActive) {
                    delay(1_000)
                    model.lock.withLock { model.reconcileLocked(model.latestRows) }
                }
            }
            return model
        }
    }
}
