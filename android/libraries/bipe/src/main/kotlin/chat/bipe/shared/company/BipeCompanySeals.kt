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
import chat.bipe.shared.BipeTrustSources
import chat.bipe.shared.BipeServers
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeBadgeGoneReason
import chat.bipe.shared.api.BipeBadgeLookup
import chat.bipe.shared.api.badge
import chat.bipe.shared.api.badgeProofs
import chat.bipe.shared.runCatchingExceptions
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** A person's seal: a `pix` or `govbr` badge (bipe#596, ADR 0239). The iOS store has the same states. */
@Immutable
sealed interface BipePersonSealState {
    /** No seal and no notice. */
    data object None : BipePersonSealState

    /** A verified name: a valid badge, bound to the user's current master key. */
    data class Verified(val payload: BadgePayload) : BipePersonSealState

    /** The user had a seal and its badge no longer verifies: a master key reset. */
    data object Suspended : BipePersonSealState
}

/** The compact mark next to a name (spec person-profile notes). The same in every place. */
enum class BipeSealMark {
    /** Assurance bank: `sealCheck` regular (ADR 0275). */
    PERSON_BANK,

    /** Assurance state: `sealCheck` fill (ADR 0275). */
    PERSON_STATE,
    COMPANY,
    PERSON_SUSPENDED,
    COMPANY_SUSPENDED,
}

/**
 * Company and person seals for one user session.
 *
 * Reads are synchronous like [chat.bipe.shared.BipeHandleResolver]: [seal] returns the cached state,
 * fetches `GET /v1/badges/{userId}` when the entry is stale, and [states] emits when the answer arrives.
 * The store fails closed: an error never keeps a seal.
 */
class BipeCompanySeals(
    private val apiClient: BipeApiClient,
    private val scope: CoroutineScope,
    /** The user's cross-signing master key, null when unknown. Throws when the lookup fails. */
    private val masterKey: suspend (String) -> String?,
    /** User IDs with a seal seen before, kept per signed-in account. Only these get a notice. */
    private val seen: BipeCompanySealsSeen,
    private val verifier: Lazy<BadgeVerifier> = lazy(BadgeVerifier::bipeTrusted),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(
        val state: BipeCompanySealState,
        val expiryMillis: Long,
        val institution: BadgePayload? = null,
        val person: BipePersonSealState = BipePersonSealState.None,
    )

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** User ID to seal state. Emits on every cache change. */
    val states: StateFlow<Map<String, BipeCompanySealState>> get() = statesFlow.asStateFlow()
    private val statesFlow = MutableStateFlow<Map<String, BipeCompanySealState>>(emptyMap())

    /** User ID to verified `institution` badge (bipe#245): its messages show as official notice cards. */
    val institutions: StateFlow<Map<String, BadgePayload>> get() = institutionsFlow.asStateFlow()
    private val institutionsFlow = MutableStateFlow<Map<String, BadgePayload>>(emptyMap())

    /** User ID to person seal (bipe#596). Holds only users with a seal or a suspended seal. */
    val personSeals: StateFlow<Map<String, BipePersonSealState>> get() = personSealsFlow.asStateFlow()
    private val personSealsFlow = MutableStateFlow<Map<String, BipePersonSealState>>(emptyMap())

    private val lock = Any()
    private val inFlight = mutableSetOf<String>()

    /** The cached seal of [userId]. Queues a fetch when the entry is stale. */
    fun seal(userId: String): BipeCompanySealState {
        if (!BipeServers.isBipeUserId(userId)) return BipeCompanySealState.None
        val now = clock()
        val entry = entries.value[userId]
        if (entry == null || entry.expiryMillis <= now) fetch(userId)
        val state = entry?.state ?: BipeCompanySealState.None
        // An expired badge never shows, also while the new fetch runs.
        return if (state is BipeCompanySealState.Verified && state.payload.exp * 1000 <= now) BipeCompanySealState.None else state
    }

    /** The verified institution badge of [userId], or null. Queues a fetch like [seal]. Fail closed: no fresh answer, no notice. */
    fun institution(userId: String): BadgePayload? {
        seal(userId)
        return institutionsFlow.value[userId]?.takeIf { it.exp * 1000 > clock() }
    }

    /** The person seal of [userId]. Queues a fetch like [seal]. An expired badge never shows. */
    fun personSeal(userId: String): BipePersonSealState {
        seal(userId)
        val state = personSealsFlow.value[userId] ?: BipePersonSealState.None
        return if (state is BipePersonSealState.Verified && state.payload.exp * 1000 <= clock()) BipePersonSealState.None else state
    }

    /** The verified badge of a company or a person, or null. */
    fun verifiedBadge(userId: String): BadgePayload? =
        (personSeal(userId) as? BipePersonSealState.Verified)?.payload ?: (seal(userId) as? BipeCompanySealState.Verified)?.payload

    /** The compact mark next to a name, or null. A revoked company seal has no mark. */
    fun mark(userId: String): BipeSealMark? = when (val person = personSeal(userId)) {
        // The strongest proof only (ADR 0275 point 15). The server answers the strongest; the mark comes from the badge.
        is BipePersonSealState.Verified -> BipePersonProofs.proofs(listOf(person.payload)).firstOrNull()?.let {
            if (it.isFill) BipeSealMark.PERSON_STATE else BipeSealMark.PERSON_BANK
        }
        BipePersonSealState.Suspended -> BipeSealMark.PERSON_SUSPENDED
        BipePersonSealState.None -> when (seal(userId)) {
            is BipeCompanySealState.Verified -> BipeSealMark.COMPANY
            BipeCompanySealState.Suspended -> BipeSealMark.COMPANY_SUSPENDED
            else -> null
        }
    }

    /** The strongest proof that verified on this device, or null. */
    fun strongestProof(userId: String): BipePersonProof? =
        (personSeal(userId) as? BipePersonSealState.Verified)?.let { BipePersonProofs.proofs(listOf(it.payload)).firstOrNull() }

    /** The store has an answer for the account, so "no seal" is a fact and not a fetch that still runs. */
    fun hasAnswer(userId: String): Boolean {
        seal(userId)
        return entries.value.containsKey(userId)
    }

    /**
     * Every person proof of the account that verifies on this device, strongest first (ADR 0275).
     * A failed call, no master key or a badge that fails gives no proof for it (fail closed).
     */
    suspend fun proofs(userId: String): List<BipePersonProof> {
        if (!BipeServers.isBipeUserId(userId)) return emptyList()
        return runCatchingExceptions {
            val envelopes = apiClient.badgeProofs(userId)
            val key = masterKey(userId) ?: return@runCatchingExceptions emptyList()
            val now = clock()
            BipePersonProofs.proofs(
                envelopes.mapNotNull { json ->
                    (verifier.value.verify(envelopeJson = json, sender = userId, senderMasterKey = key, nowMillis = now) as? BadgeVerificationResult.Valid)?.payload
                },
            )
        }.getOrDefault(emptyList())
    }

    private fun fetch(userId: String) {
        synchronized(lock) {
            if (!inFlight.add(userId)) return
        }
        scope.launch {
            try {
                val entry = runCatchingExceptions { load(userId) }.getOrElse {
                    // Fail closed: drop a seal, keep a notice, and retry soon.
                    val old = entries.value[userId]?.state
                    val kept = if (old is BipeCompanySealState.Revoked || old is BipeCompanySealState.Suspended) old else BipeCompanySealState.None
                    val person = personSealsFlow.value[userId] as? BipePersonSealState.Suspended ?: BipePersonSealState.None
                    Entry(kept, clock() + ERROR_TTL_MILLIS, person = person)
                }
                entries.update { it + (userId to entry) }
                statesFlow.update { it + (userId to entry.state) }
                institutionsFlow.update { if (entry.institution != null) it + (userId to entry.institution) else it - userId }
                personSealsFlow.update { if (entry.person != BipePersonSealState.None) it + (userId to entry.person) else it - userId }
            } finally {
                synchronized(lock) { inFlight.remove(userId) }
            }
        }
    }

    private suspend fun load(userId: String): Entry {
        val lookup = apiClient.badge(userId)
        val now = clock()
        val personKey = "person:$userId" // Shares the persisted set with company seals. A user ID starts with @, so the keys differ.
        val state = when (lookup) {
            BipeBadgeLookup.NoBadge -> {
                seen.remove(userId)
                seen.remove(personKey)
                BipeCompanySealState.None
            }
            is BipeBadgeLookup.Gone -> {
                seen.remove(personKey)
                if (seen.contains(userId)) BipeCompanySealState.Revoked(lookup.reason) else BipeCompanySealState.None
            }
            is BipeBadgeLookup.Envelope -> {
                val key = masterKey(userId)
                val result = verifier.value.verify(
                    envelopeJson = lookup.json,
                    sender = userId,
                    senderMasterKey = key,
                    nowMillis = now,
                )
                when {
                    result is BadgeVerificationResult.Valid && result.payload.lvl == BadgeLevel.CNPJ -> {
                        seen.add(userId)
                        return Entry(BipeCompanySealState.Verified(result.payload), minOf(result.payload.exp * 1000, now + TTL_MILLIS))
                    }
                    result is BadgeVerificationResult.Valid && result.payload.lvl == BadgeLevel.INSTITUTION ->
                        return Entry(BipeCompanySealState.None, minOf(result.payload.exp * 1000, now + TTL_MILLIS), result.payload)
                    result is BadgeVerificationResult.Valid && (result.payload.lvl == BadgeLevel.PIX || result.payload.lvl == BadgeLevel.GOVBR) -> {
                        // The seal shows only when identityVerified holds (registries/trust-facts.json).
                        val identity = BipeTrustSources.Identity(key, isVerified = false, hasVerificationViolation = false)
                        val facts = BipeTrustSources.profileFacts(userId, identity, BipeTrustSources.Seal.Verified(result.payload.name))
                        if (facts.identity != "verified") return Entry(BipeCompanySealState.None, now + TTL_MILLIS)
                        seen.add(personKey)
                        return Entry(
                            BipeCompanySealState.None,
                            minOf(result.payload.exp * 1000, now + TTL_MILLIS),
                            person = BipePersonSealState.Verified(result.payload),
                        )
                    }
                    result is BadgeVerificationResult.Valid -> BipeCompanySealState.None
                    // A badge that does not verify suspends only a seal this phone saw before.
                    seen.contains(personKey) -> return Entry(BipeCompanySealState.None, now + TTL_MILLIS, person = BipePersonSealState.Suspended)
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
