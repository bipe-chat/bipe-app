// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import chat.bipe.shared.Base64Url
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgeVerificationResult
import chat.bipe.shared.BadgeVerifier
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.brandMarks
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

/** One verified brand mark on a reseller's work account (ADR 0162, spec brand-mark). The iOS store has the same. */
@Immutable
data class BipeBrandMark(
    /** The mark ID. */
    val id: String,
    /** The brand's Matrix user ID. */
    val brand: String,
    /** The brand's name, as in the brand's own CNPJ badge. */
    val brandName: String,
    /** The brand's normalised CNPJ. */
    val cnpj: String,
    /** One id of [BipeRegistries.BRAND_MARK_LABELS]. */
    val label: String,
)

/** The marks of one account, and the brand of a mark this phone showed that is gone now. */
@Immutable
data class BipeBrandMarksState(val marks: List<BipeBrandMark> = emptyList(), val endedBrand: String? = null)

/**
 * Brand marks for one user session (ADR 0162).
 *
 * Reads are synchronous like [BipeCompanySeals]: [state] returns the cached state, fetches
 * `GET /v1/brand-marks/{userId}` when the entry is stale, and [states] emits when the answer arrives. An answer is kept
 * for [BipeRegistries.BRAND_MARK_RECHECK_MINUTES], so an ended mark goes within that time. A failed fetch shows no
 * mark and no notice. A fresh answer without a mark this phone showed gives the ended notice for that brand, once.
 */
class BipeBrandMarks(
    private val apiClient: BipeApiClient,
    /** Only Matrix IDs on this server can have a mark. */
    private val serverName: String,
    private val scope: CoroutineScope,
    /** The user's cross-signing master key, null when unknown. Throws when the lookup fails. */
    private val masterKey: suspend (String) -> String?,
    /** The marks shown on this phone, kept per signed-in account. */
    private val seen: BipeBrandMarksSeen,
    private val verifier: Lazy<BadgeVerifier> = lazy(BadgeVerifier::bipeTrusted),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val state: BipeBrandMarksState, val expiryMillis: Long)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** User ID to marks. Emits on every cache change. */
    val states: StateFlow<Map<String, BipeBrandMarksState>> get() = statesFlow.asStateFlow()
    private val statesFlow = MutableStateFlow<Map<String, BipeBrandMarksState>>(emptyMap())

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    /** The cached marks of [userId]. Queues a fetch when the entry is stale. */
    fun state(userId: String): BipeBrandMarksState {
        if (!userId.startsWith("@") || !userId.endsWith(":$serverName")) return BipeBrandMarksState()
        val entry = entries.value[userId]
        if (entry == null || entry.expiryMillis <= clock()) fetch(userId)
        return entry?.state ?: BipeBrandMarksState()
    }

    private fun fetch(userId: String) {
        synchronized(lock) {
            if (!inFlight.add(userId)) return
        }
        scope.launch {
            try {
                val entry = runCatchingExceptions { load(userId) }.getOrElse { Entry(BipeBrandMarksState(), clock() + ERROR_TTL_MILLIS) }
                entries.update { it + (userId to entry) }
                statesFlow.update { it + (userId to entry.state) }
            } finally {
                synchronized(lock) { inFlight.remove(userId) }
            }
        }
    }

    private suspend fun load(userId: String): Entry {
        val envelopes = apiClient.brandMarks(userId)
        val key = if (envelopes.isEmpty()) null else masterKey(userId)
        val now = clock()
        val marks = mutableListOf<BipeBrandMark>()
        for (json in envelopes) {
            val result = verifier.value.verify(envelopeJson = json, sender = userId, senderDevice = "", senderMasterKey = key, nowMillis = now)
            val payload = (result as? BadgeVerificationResult.Valid)?.payload ?: continue
            if (payload.lvl != BadgeLevel.BRAND_MARK) continue
            val id = payload.mark ?: continue
            if (marks.any { it.id == id }) continue
            marks += BipeBrandMark(id, payload.brand ?: continue, payload.name, payload.cnpj ?: continue, payload.label ?: continue)
        }
        val shown = marks.take(BipeRegistries.BRAND_MARK_MAX_PER_ACCOUNT)

        // Only the server's answer ends a mark: a mark that is still listed but does not verify is hidden, not ended.
        val listed = envelopes.mapNotNull(::unverifiedMarkId).toSet()
        val before = seen.marks(userId)
        val ended = before.filterKeys { it !in listed }
        seen.set(userId, before.filterKeys { it in listed } + shown.associate { it.id to it.brandName })
        return Entry(BipeBrandMarksState(shown, ended.values.sorted().firstOrNull()), now + TTL_MILLIS)
    }

    private companion object {
        val TTL_MILLIS = BipeRegistries.BRAND_MARK_RECHECK_MINUTES.minutes.inWholeMilliseconds
        val ERROR_TTL_MILLIS = 2.minutes.inWholeMilliseconds
        val json = Json { ignoreUnknownKeys = true }

        /** The `mark` field of an envelope's payload, read without trust: it only tells which marks the server lists. */
        fun unverifiedMarkId(envelope: String): String? = runCatching {
            val payload = json.parseToJsonElement(envelope).jsonObject["payload"]?.jsonPrimitive?.content ?: return null
            val bytes = Base64Url.decode(payload) ?: return null
            json.parseToJsonElement(bytes.decodeToString()).jsonObject["mark"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

/** The persisted marks shown on this phone: user ID to mark ID to brand name. One key per signed-in account. */
class BipeBrandMarksSeen(
    private val preferences: Lazy<SharedPreferences>,
    sessionUserId: String,
) {
    private val key = "seen_$sessionUserId"
    private val lock = Any()
    private var cache: Map<String, Map<String, String>>? = null
    private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))

    fun marks(userId: String): Map<String, String> = synchronized(lock) { read()[userId].orEmpty() }

    fun set(userId: String, marks: Map<String, String>) = synchronized(lock) {
        val old = read()
        val new = if (marks.isEmpty()) old - userId else old + (userId to marks)
        if (new != old) {
            cache = new
            preferences.value.edit().putString(key, Json.encodeToString(serializer, new)).apply()
        }
    }

    private fun read(): Map<String, Map<String, String>> = cache
        ?: (preferences.value.getString(key, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap())
            .also { cache = it }
}
