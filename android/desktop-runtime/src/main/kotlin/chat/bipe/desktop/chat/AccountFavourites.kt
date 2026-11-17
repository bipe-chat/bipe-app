// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.settings.MatrixSettingsReader
import chat.bipe.shared.BipeFavouritesOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.Membership

sealed interface FavouriteIntent {
    val room: String
    data class Set(override val room: String, val value: Boolean) : FavouriteIntent
    data class Move(override val room: String, val index: Int, val shown: List<String>) : FavouriteIntent
}
sealed interface FavouritesSync {
    data object Idle : FavouritesSync
    data object Saving : FavouritesSync
    /** Edits not yet protected on disk. An empty list means the durable pending queue needs replay. */
    data class Failed(val edits: List<FavouriteIntent> = emptyList()) : FavouritesSync
}
sealed interface FavouritesPhase {
    data object Loading : FavouritesPhase
    data object Unavailable : FavouritesPhase
    data class Ready(val order: List<String>, val sync: FavouritesSync) : FavouritesPhase
}
internal interface FavouritesBackend {
    suspend fun cached(): String?
    suspend fun fresh(): String?
    suspend fun setFavourite(room: String, value: Boolean)
    suspend fun checkFavourite(room: String)
    suspend fun write(value: String)
}
internal interface FavouritesJournal { fun read(): String?; fun write(value: String) }

/** Account-owned tag actions and protected pending order edits. Fresh reads preserve other scopes and future fields. */
class AccountFavourites private constructor(
    private val group: AccountTaskGroup,
    private val backend: FavouritesBackend,
    private val journal: FavouritesJournal,
) {
    private data class Record(val order: List<String>, val pending: List<FavouriteIntent>, val observed: JsonElement?)
    private var record = Record(emptyList(), emptyList(), null)
    private val mutex = Mutex()
    private val mutablePhase = MutableStateFlow<FavouritesPhase>(FavouritesPhase.Loading)
    val phase = mutablePhase.asStateFlow()
    @Volatile private var revision = 0L

    fun set(room: String, value: Boolean) { if (room.isNotBlank()) change(FavouriteIntent.Set(room, value)) }
    fun move(room: String, index: Int, shown: List<String>) {
        if (shown.distinct().size == shown.size && room in shown && index in shown.indices)
            change(FavouriteIntent.Move(room, index, shown.toList()))
    }

    private fun change(intent: FavouriteIntent) {
        var edits: List<FavouriteIntent>? = null
        group.publish {
            val current = mutablePhase.value as? FavouritesPhase.Ready
            if (current != null && current.sync != FavouritesSync.Saving) {
                edits = (current.sync as? FavouritesSync.Failed)?.edits.orEmpty() + intent
                revision++
                mutablePhase.value = current.copy(sync = FavouritesSync.Saving)
            }
        }
        val accepted = edits ?: return
        group.launch { mutex.withLock { apply(accepted) } }
    }

    private suspend fun apply(edits: List<FavouriteIntent>) {
        val next = record.copy(order = reconcile(record.order, edits), pending = record.pending + edits)
        try { persist(next) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(FavouritesSync.Failed(edits)); return }
        record = next
        publish(FavouritesSync.Saving)
        replay()
    }

    private suspend fun replay() {
        try { flush() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(FavouritesSync.Failed()) }
    }

    fun retry() = group.launch { mutex.withLock {
        if (mutablePhase.value == FavouritesPhase.Unavailable) {
            try { load() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock }
        }
        val current = mutablePhase.value as? FavouritesPhase.Ready ?: return@withLock
        if (current.sync == FavouritesSync.Saving) return@withLock
        val edits = (current.sync as? FavouritesSync.Failed)?.edits.orEmpty()
        if (edits.isNotEmpty()) { publish(FavouritesSync.Saving); apply(edits) }
        else if (record.pending.isNotEmpty()) { publish(FavouritesSync.Saving); replay() }
    } }

    private suspend fun load() {
        val saved = journal.read()
        val restored = if (saved == null) Record(emptyList(), emptyList(), null) else {
            val json = Json.parseToJsonElement(saved).jsonObject
            check(json.getValue("version").jsonPrimitive.int == 1)
            Record(json.getValue("order").jsonArray.map(::roomId),
                json.getValue("pending").jsonArray.map(::decode), json["observed"]?.takeUnless { it == JsonNull })
        }
        val cached = snapshot(backend.cached())
        record = if (cached == null || cached == restored.observed) restored else
            Record(reconcile(BipeFavouritesOrder.parse(cached.toString()), restored.pending), restored.pending, cached).also(::persist)
        publish(if (record.pending.isEmpty()) FavouritesSync.Idle else FavouritesSync.Failed())
    }

    internal suspend fun refreshCached() {
        if (mutablePhase.value !is FavouritesPhase.Ready || (mutablePhase.value as FavouritesPhase.Ready).sync == FavouritesSync.Saving) return
        val started = revision
        val cached = snapshot(backend.cached()) ?: return
        if (cached == record.observed || started != revision) return
        val next = Record(reconcile(BipeFavouritesOrder.parse(cached.toString()), record.pending), record.pending, cached)
        persist(next)
        record = next
        group.publish {
            val current = mutablePhase.value as? FavouritesPhase.Ready
            if (started == revision && current?.sync != FavouritesSync.Saving) {
                val edits = (current?.sync as? FavouritesSync.Failed)?.edits.orEmpty()
                mutablePhase.value = FavouritesPhase.Ready(next.order, if (edits.isNotEmpty() || next.pending.isNotEmpty()) FavouritesSync.Failed(edits) else FavouritesSync.Idle)
            }
        }
    }

    private suspend fun flush() {
        group.requireCurrent()
        val server = backend.fresh()?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        var order = BipeFavouritesOrder.parse(server.toString())
        for (intent in record.pending) {
            group.requireCurrent()
            when (intent) {
                is FavouriteIntent.Set -> backend.setFavourite(intent.room, intent.value)
                is FavouriteIntent.Move -> backend.checkFavourite(intent.room)
            }
            group.requireCurrent()
            order = reconcile(order, listOf(intent))
        }
        val canonical = Json.parseToJsonElement(BipeFavouritesOrder.content(order)).jsonObject
        backend.write(JsonObject(server + canonical).toString())
        group.requireCurrent()
        val acknowledged = Record(BipeFavouritesOrder.parse(canonical.toString()), emptyList(), snapshot(backend.cached()) ?: record.observed)
        persist(acknowledged)
        record = acknowledged
        publish(FavouritesSync.Idle)
    }

    private fun persist(value: Record) {
        group.requireCurrent()
        journal.write(buildJsonObject {
            put("version", 1); put("order", JsonArray(value.order.map(::JsonPrimitive)))
            put("pending", JsonArray(value.pending.map(::encode))); put("observed", value.observed ?: JsonNull)
        }.toString())
    }
    private fun publish(sync: FavouritesSync) = group.publish { mutablePhase.value = FavouritesPhase.Ready(record.order.toList(), sync) }

    companion object {
        private fun snapshot(raw: String?): JsonElement? = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrElse { _ -> JsonPrimitive(it) } }
        private fun reconcile(order: List<String>, edits: List<FavouriteIntent>): List<String> = edits.fold(order) { result, edit ->
            when (edit) {
                is FavouriteIntent.Set -> if (edit.value) BipeFavouritesOrder.adding(edit.room, result) else BipeFavouritesOrder.removing(edit.room, result)
                is FavouriteIntent.Move -> BipeFavouritesOrder.moving(edit.room, edit.index, edit.shown, result)
            }
        }
        private fun encode(intent: FavouriteIntent): JsonObject = buildJsonObject {
            put("room", intent.room)
            when (intent) {
                is FavouriteIntent.Set -> { put("kind", "set"); put("value", intent.value) }
                is FavouriteIntent.Move -> { put("kind", "move"); put("index", intent.index); put("shown", JsonArray(intent.shown.map(::JsonPrimitive))) }
            }
        }
        private fun decode(value: JsonElement): FavouriteIntent {
            val json = value.jsonObject
            val room = roomId(json.getValue("room"))
            return when (json.getValue("kind").jsonPrimitive.content) {
                "set" -> FavouriteIntent.Set(room, json.getValue("value").jsonPrimitive.boolean)
                "move" -> {
                    val shown = json.getValue("shown").jsonArray.map(::roomId)
                    val index = json.getValue("index").jsonPrimitive.int
                    check(shown.distinct().size == shown.size && room in shown && index in shown.indices)
                    FavouriteIntent.Move(room, index, shown)
                }
                else -> error("Unsupported favourites journal")
            }
        }
        private fun roomId(value: JsonElement): String = value.jsonPrimitive.let {
            check(it.isString && it.content.isNotBlank()); it.content
        }

        suspend fun open(runtime: MatrixAccountRuntime): AccountFavourites {
            val reader = MatrixSettingsReader(runtime.client)
            suspend fun withJoinedRoom(id: String, favourite: Boolean?, operation: suspend (org.matrix.rustcomponents.sdk.Room) -> Unit) {
                val room = runtime.client.getRoom(id) ?: error("Favourite room unavailable")
                try {
                    val info = room.roomInfo()
                    try { check(info.membership == Membership.JOINED && !info.isSpace && !info.isLowPriority && (favourite != true || info.isFavourite)) }
                    finally { info.destroy() }
                    operation(room)
                } finally { room.destroy() }
            }
            return create(runtime.lifetime, object : FavouritesBackend {
                override suspend fun cached() = runtime.client.accountData(BipeFavouritesOrder.EVENT_TYPE)
                override suspend fun fresh() = reader.accountData(BipeFavouritesOrder.EVENT_TYPE)
                override suspend fun setFavourite(room: String, value: Boolean) = runtime.withRoomTagWrite {
                    withJoinedRoom(room, null) { it.setIsFavourite(value, null) }
                }
                override suspend fun checkFavourite(room: String) = withJoinedRoom(room, true) { }
                override suspend fun write(value: String) = runtime.client.setAccountData(BipeFavouritesOrder.EVENT_TYPE, value)
            }, object : FavouritesJournal {
                override fun read() = runtime.lifetime.lease.readFavourites()
                override fun write(value: String) = runtime.lifetime.lease.writeFavourites(value)
            }, poll = true)
        }

        internal suspend fun create(owner: AccountLifetime, backend: FavouritesBackend, journal: FavouritesJournal, poll: Boolean = false): AccountFavourites {
            val group = AccountTaskGroup.create(owner)
            val model = AccountFavourites(group, backend, journal)
            try { group.async { model.mutex.withLock { model.load() } }.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { model.mutablePhase.value = FavouritesPhase.Unavailable } }
            if (poll) group.launch {
                if (model.record.pending.isNotEmpty()) model.retry().join()
                while (currentCoroutineContext().isActive) {
                    delay(5_000)
                    try { model.mutex.withLock { model.refreshCached() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Preserve protected edits; cached sync can retry. */ }
                }
            }
            return model
        }
    }
}
