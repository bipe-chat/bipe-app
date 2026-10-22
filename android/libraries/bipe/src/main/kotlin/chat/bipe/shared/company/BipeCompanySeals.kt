// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import chat.bipe.shared.BadgeLevel
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.BadgeVerificationResult
import chat.bipe.shared.BadgeVerifier
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeBadgeGoneReason
import chat.bipe.shared.api.BipeBadgeLookup
import chat.bipe.shared.runCatchingExceptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes

/** The company seal of one user (ADR 0042, bipe#252). The iOS store has the same states. */
@Immutable
sealed interface BipeCompanySealState {
    /** No seal and no notice. */
    data object None : BipeCompanySealState

    /** A valid `cnpj` badge, bound to the user's current master key. */
    data class Verified(val payload: BadgePayload) : BipeCompanySealState

    /** The server ended a seal that this account saw before. */
    data class Revoked(val reason: BipeBadgeGoneReason) : BipeCompanySealState

    /** The server sends a badge that does not verify, for a user with a seal seen before (example: a new master key). */
    data object Suspended : BipeCompanySealState
}

/**
 * Company seals for one user session.
 *
 * Reads are synchronous like [chat.bipe.shared.BipePublicProfiles]: [seal] returns the cached state,
 * fetches `GET /v1/badges/{userId}` when the entry is stale, and [states] emits when the answer arrives.
 * The store fails closed: an error never keeps a seal.
 */
class BipeCompanySeals(
    private val apiClient: BipeApiClient,
    /** Only Matrix IDs on this server can have a seal. */
    private val serverName: String,
    private val scope: CoroutineScope,
    /** The user's cross-signing master key, null when unknown. Throws when the lookup fails. */
    private val masterKey: suspend (String) -> String?,
    /** User IDs with a seal seen before, kept per signed-in account. Only these get a notice. */
    private val seen: BipeCompanySealsSeen,
    private val verifier: Lazy<BadgeVerifier> = lazy(BadgeVerifier::bipeTrusted),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val state: BipeCompanySealState, val expiryMillis: Long)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** User ID to seal state. Emits on every cache change. */
    val states: StateFlow<Map<String, BipeCompanySealState>> get() = statesFlow.asStateFlow()
    private val statesFlow = MutableStateFlow<Map<String, BipeCompanySealState>>(emptyMap())

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    /** The cached seal of [userId]. Queues a fetch when the entry is stale. */
    fun seal(userId: String): BipeCompanySealState {
        if (!userId.startsWith("@") || !userId.endsWith(":$serverName")) return BipeCompanySealState.None
        val now = clock()
        val entry = entries.value[userId]
        if (entry == null || entry.expiryMillis <= now) fetch(userId)
        val state = entry?.state ?: BipeCompanySealState.None
        // An expired badge never shows, also while the new fetch runs.
        return if (state is BipeCompanySealState.Verified && state.payload.exp * 1000 <= now) BipeCompanySealState.None else state
    }

    private fun fetch(userId: String) {
        synchronized(lock) {
            if (!inFlight.add(userId)) return
        }
        scope.launch {
            try {
                val entry = runCatchingExceptions { load(userId) }.getOrElse { error ->
                    Timber.w(error, "Bipe company seal lookup failed for %s", userId)
                    // Fail closed: drop a seal, keep a notice, and retry soon.
                    val old = entries.value[userId]?.state
                    val kept = if (old is BipeCompanySealState.Revoked || old is BipeCompanySealState.Suspended) old else BipeCompanySealState.None
                    Entry(kept, clock() + ERROR_TTL_MILLIS)
                }
                entries.update { it + (userId to entry) }
                statesFlow.update { it + (userId to entry.state) }
            } finally {
                synchronized(lock) { inFlight.remove(userId) }
            }
        }
    }

    private suspend fun load(userId: String): Entry {
        val lookup = apiClient.badge(userId)
        val now = clock()
        val state = when (lookup) {
            BipeBadgeLookup.None -> {
                seen.remove(userId)
                BipeCompanySealState.None
            }
            is BipeBadgeLookup.Gone -> if (seen.contains(userId)) BipeCompanySealState.Revoked(lookup.reason) else BipeCompanySealState.None
            is BipeBadgeLookup.Envelope -> {
                val result = verifier.value.verify(
                    envelopeJson = lookup.json,
                    sender = userId,
                    senderDevice = "",
                    senderMasterKey = masterKey(userId),
                    nowMillis = now,
                )
                when {
                    result is BadgeVerificationResult.Valid && result.payload.lvl == BadgeLevel.CNPJ -> {
                        seen.add(userId)
                        return Entry(BipeCompanySealState.Verified(result.payload), minOf(result.payload.exp * 1000, now + TTL_MILLIS))
                    }
                    result is BadgeVerificationResult.Valid -> BipeCompanySealState.None
                    seen.contains(userId) -> BipeCompanySealState.Suspended
                    else -> BipeCompanySealState.None
                }
            }
        }
        return Entry(state, now + TTL_MILLIS)
    }

    private companion object {
        val TTL_MILLIS = 10.minutes.inWholeMilliseconds
        val ERROR_TTL_MILLIS = 2.minutes.inWholeMilliseconds
    }
}

/** The persisted set of user IDs with a seal seen before. One key per signed-in account. */
class BipeCompanySealsSeen(
    private val preferences: Lazy<SharedPreferences>,
    sessionUserId: String,
) {
    private val key = "seen_$sessionUserId"
    private val lock = Any()
    private var cache: Set<String>? = null

    fun contains(userId: String): Boolean = synchronized(lock) { userId in read() }

    fun add(userId: String) = change { it + userId }

    fun remove(userId: String) = change { it - userId }

    private fun change(transform: (Set<String>) -> Set<String>) = synchronized(lock) {
        val old = read()
        val new = transform(old)
        if (new != old) {
            cache = new
            preferences.value.edit().putStringSet(key, new).apply()
        }
    }

    private fun read(): Set<String> = cache ?: preferences.value.getStringSet(key, null).orEmpty().toSet().also { cache = it }
}
