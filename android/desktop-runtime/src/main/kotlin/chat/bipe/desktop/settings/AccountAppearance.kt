// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.settings

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.shared.BipeSyncedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class AppearancePolicy(val palettes: List<String>, val defaultPalette: String, val backgrounds: List<String>, val defaultBackground: String)
data class AppearanceValues(val settings: Map<String, JsonElement>) {
    fun palette(policy: AppearancePolicy) = BipeSyncedSettings.string(settings, BipeSyncedSettings.PALETTE)
        ?.takeIf { it in policy.palettes } ?: policy.defaultPalette
    fun background(policy: AppearancePolicy) = BipeSyncedSettings.string(settings, BipeSyncedSettings.CHAT_BACKGROUND)
        ?.takeIf { it in policy.backgrounds } ?: policy.defaultBackground
}
sealed interface AppearanceRetry {
    data class Edit(val key: String, val value: String) : AppearanceRetry
    data object Pending : AppearanceRetry
}
sealed interface AppearanceSync {
    data object IDLE : AppearanceSync
    data object SAVING : AppearanceSync
    data class FAILED(val action: AppearanceRetry) : AppearanceSync
}
sealed interface AppearancePhase {
    data object Loading : AppearancePhase
    data class Ready(val values: AppearanceValues, val sync: AppearanceSync) : AppearancePhase
    data object Unavailable : AppearancePhase
}

internal interface AppearanceBackend {
    suspend fun cached(): String?
    suspend fun fresh(): String?
    suspend fun write(value: String)
}
internal interface AppearanceJournal {
    fun read(): String?
    fun write(value: String)
}

/** Per-account durable pending changes; cached sync is never used as the base of a server write. */
class AccountAppearance private constructor(
    private val group: AccountTaskGroup,
    val policy: AppearancePolicy,
    private val backend: AppearanceBackend,
    private val journal: AppearanceJournal,
) {
    private data class Record(val values: Map<String, JsonElement>, val pending: Set<String>, val observed: Map<String, JsonElement>?)
    private val mutex = Mutex()
    private var record = Record(emptyMap(), emptySet(), null)
    private val mutablePhase = MutableStateFlow<AppearancePhase>(AppearancePhase.Loading)
    val phase = mutablePhase.asStateFlow()

    fun pickPalette(id: String) { if (id in policy.palettes) change(BipeSyncedSettings.PALETTE, id) }
    fun pickBackground(id: String) { if (id in policy.backgrounds) change(BipeSyncedSettings.CHAT_BACKGROUND, id) }

    private fun change(key: String, value: String) {
        var accepted = false
        group.publish {
            val current = mutablePhase.value as? AppearancePhase.Ready
            if (current != null && current.sync != AppearanceSync.SAVING) {
                mutablePhase.value = current.copy(sync = AppearanceSync.SAVING)
                accepted = true
            }
        }
        if (!accepted) return
        group.launch { mutex.withLock {
            applyChange(key, value)
        } }
    }

    private suspend fun applyChange(key: String, value: String) {
        val next = record.copy(values = record.values + (key to JsonPrimitive(value)), pending = record.pending + key)
        try {
            persist(next) // Commit the protected journal before applying the preference or starting HTTP.
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { publish(AppearanceSync.FAILED(AppearanceRetry.Edit(key, value))); return }
        record = next
        publish(AppearanceSync.SAVING)
        try { flush() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(AppearanceSync.FAILED(AppearanceRetry.Pending)) }
    }

    fun retry() = group.launch { mutex.withLock {
        try {
            if (mutablePhase.value == AppearancePhase.Unavailable) load()
            val action = ((mutablePhase.value as? AppearancePhase.Ready)?.sync as? AppearanceSync.FAILED)?.action
            if (action is AppearanceRetry.Edit) { publish(AppearanceSync.SAVING); applyChange(action.key, action.value); return@withLock }
            if (record.pending.isNotEmpty()) { publish(AppearanceSync.SAVING); flush() }
            else publish(AppearanceSync.IDLE)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { if (mutablePhase.value is AppearancePhase.Ready) publish(AppearanceSync.FAILED(AppearanceRetry.Pending)) }
    } }

    private suspend fun load() {
        val saved = journal.read()
        val restored = if (saved == null) Record(emptyMap(), emptySet(), null) else {
            val json = Json.parseToJsonElement(saved).jsonObject
            check(json.getValue("version").jsonPrimitive.int == 1)
            val values = json.getValue("values").jsonObject
            val pending = json.getValue("pending").jsonArray.map { it.jsonPrimitive.content }.toSet()
            check(pending.all { it == BipeSyncedSettings.PALETTE || it == BipeSyncedSettings.CHAT_BACKGROUND })
            Record(values, pending, json["observed"]?.takeUnless { it == JsonNull }?.jsonObject)
        }
        val cached = backend.cached()?.let(BipeSyncedSettings::parse)
        record = if (cached == null || cached == restored.observed) restored else {
            Record(BipeSyncedSettings.reconcile(cached, restored.values, restored.pending, first = false), restored.pending, cached)
                .also(::persist)
        }
        publish(if (record.pending.isEmpty()) AppearanceSync.IDLE else AppearanceSync.FAILED(AppearanceRetry.Pending))
    }

    internal suspend fun refreshCached() {
        val current = mutablePhase.value as? AppearancePhase.Ready ?: return
        if (current.sync == AppearanceSync.SAVING) return
        val cached = backend.cached()?.let(BipeSyncedSettings::parse) ?: return
        if (cached == record.observed) return // The SDK can still hold the pre-write event until the next sync.
        val next = Record(BipeSyncedSettings.reconcile(cached, record.values, record.pending, first = false), record.pending, cached)
        persist(next)
        record = next
        val localFailure = (current.sync as? AppearanceSync.FAILED)?.takeIf { it.action is AppearanceRetry.Edit }
        publish(localFailure ?: if (next.pending.isEmpty()) AppearanceSync.IDLE else AppearanceSync.FAILED(AppearanceRetry.Pending))
    }

    private suspend fun flush() {
        group.requireCurrent()
        // A malformed fresh response must not erase unrelated privacy/unknown keys on the next PUT.
        val server = backend.fresh()?.let { Json.parseToJsonElement(it).jsonObject } ?: emptyMap()
        val merged = BipeSyncedSettings.reconcile(server, record.values, record.pending, first = false)
        backend.write(BipeSyncedSettings.content(merged))
        group.requireCurrent()
        val saved = Record(merged, emptySet(), backend.cached()?.let(BipeSyncedSettings::parse) ?: record.observed)
        persist(saved)
        record = saved
        publish(AppearanceSync.IDLE)
    }

    private fun persist(value: Record) {
        group.requireCurrent()
        journal.write(buildJsonObject {
            put("version", JsonPrimitive(1)); put("values", JsonObject(value.values))
            put("pending", JsonArray(value.pending.map(::JsonPrimitive)))
            put("observed", value.observed?.let(::JsonObject) ?: JsonNull)
        }.toString())
    }

    private fun publish(sync: AppearanceSync) = group.publish { mutablePhase.value = AppearancePhase.Ready(AppearanceValues(record.values.toMap()), sync) }

    companion object {
        suspend fun open(runtime: MatrixAccountRuntime, policy: AppearancePolicy): AccountAppearance {
            val reader = MatrixSettingsReader(runtime.client)
            return create(runtime.lifetime, policy, object : AppearanceBackend {
                override suspend fun cached() = runtime.client.accountData(BipeSyncedSettings.EVENT_TYPE)
                override suspend fun fresh() = reader.accountData(BipeSyncedSettings.EVENT_TYPE)
                override suspend fun write(value: String) = runtime.client.setAccountData(BipeSyncedSettings.EVENT_TYPE, value)
            }, object : AppearanceJournal {
                override fun read() = runtime.lifetime.lease.readAppearance()
                override fun write(value: String) = runtime.lifetime.lease.writeAppearance(value)
            }, poll = true)
        }

        internal suspend fun create(owner: AccountLifetime, policy: AppearancePolicy, backend: AppearanceBackend,
                                    journal: AppearanceJournal, poll: Boolean = false): AccountAppearance {
            val group = AccountTaskGroup.create(owner)
            val model = AccountAppearance(group, policy, backend, journal)
            try { group.async { model.mutex.withLock { model.load() } }.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { group.publish { model.mutablePhase.value = AppearancePhase.Unavailable } }
            if (poll) group.launch {
                if (model.mutablePhase.value is AppearancePhase.Ready && model.record.pending.isNotEmpty()) model.retry().join()
                while (currentCoroutineContext().isActive) {
                    delay(5_000)
                    try { model.mutex.withLock { model.refreshCached() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Cached choices stay available; the next sync retries. */ }
                }
            }
            return model
        }
    }
}
