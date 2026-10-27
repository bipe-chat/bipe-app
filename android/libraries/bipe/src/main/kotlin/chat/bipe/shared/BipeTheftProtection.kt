// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.SharedPreferences
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeOpenIdToken
import chat.bipe.shared.generated.BipeRegistries
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// Theft protection (ADR 0044, ADR 0051, bipe-chat/bipe#600, #268). Contract: GuardianState, Guardian,
// GuardedAccount and TheftMark in bipe-shared api/openapi.yaml. Statement: bipe-shared docs/theft-mark.md.
// Same calls and rules as BipeKit BipeTheftProtection.swift on iOS.

/** The answer of `GET /v1/me/guardians` and `PUT /v1/me/guardians`. */
@Serializable
data class BipeGuardianState(
    /** The caller's guardians, in the server's order. At most [BipeRegistries.THEFT_MAX_GUARDIANS]. */
    val guardians: List<BipeGuardian> = emptyList(),
    /** The accounts that chose the caller as a guardian. */
    val guarding: List<BipeGuardedAccount> = emptyList(),
    /** The caller's most recent mark, after it was cleared. Null when the account was never marked. */
    @SerialName("last_mark") val lastMark: BipeTheftMark? = null,
)

@Serializable
data class BipeGuardian(
    @SerialName("user_id") val userId: String,
    @SerialName("added_at") val addedAt: String,
)

@Serializable
data class BipeGuardedAccount(
    @SerialName("user_id") val userId: String,
    /** Set while a mark is on. */
    val mark: BipeTheftMark? = null,
)

@Serializable
data class BipeTheftMark(
    @SerialName("user_id") val userId: String,
    /** ISO 8601. */
    @SerialName("stolen_at") val stolenAt: String,
    /** The guardian who set the mark. The owner sees it (ADR 0044). */
    @SerialName("marked_by") val markedBy: String,
    @SerialName("cleared_at") val clearedAt: String? = null,
) {
    /** [stolenAt] as an instant, null when it does not parse. */
    val stolenInstant: Instant? get() = runCatchingExceptions { Instant.parse(stolenAt) }.getOrNull()
}

@Serializable
private data class GuardiansRequest(@SerialName("user_ids") val userIds: List<String>)

@Serializable
private data class PhoneRequest(@SerialName("phone_id") val phoneId: String)

@Serializable
private data class TheftMarksResponse(val marks: List<BadgeEnvelope> = emptyList())

/** `GET /v1/me/guardians`. */
suspend fun BipeApiClient.guardians(accessToken: String): BipeGuardianState =
    send("GET", listOf("v1", "me", "guardians"), accessToken = accessToken, response = BipeGuardianState.serializer())

/** `PUT /v1/me/guardians`. Replaces the list (0 to [BipeRegistries.THEFT_MAX_GUARDIANS] IDs). */
suspend fun BipeApiClient.setGuardians(userIds: List<String>, accessToken: String): BipeGuardianState = send(
    "PUT",
    listOf("v1", "me", "guardians"),
    body = encode(GuardiansRequest.serializer(), GuardiansRequest(userIds)),
    accessToken = accessToken,
    response = BipeGuardianState.serializer(),
)

/** `PUT /v1/me/phone`. Records this phone's ID for the account (bipe-chat/bipe#268). */
suspend fun BipeApiClient.reportPhone(phoneId: String, accessToken: String) {
    send<Unit>(
        "PUT",
        listOf("v1", "me", "phone"),
        body = encode(PhoneRequest.serializer(), PhoneRequest(phoneId)),
        accessToken = accessToken,
        response = null,
    )
}

/** `POST /v1/theft/{userId}/mark`. Only a guardian of the account; else `not_found`. */
suspend fun BipeApiClient.markStolen(userId: String, accessToken: String): BipeTheftMark =
    send("POST", listOf("v1", "theft", userId, "mark"), accessToken = accessToken, response = BipeTheftMark.serializer())

/** `POST /v1/theft/{userId}/clear`. Only a guardian of the account; else `not_found`. */
suspend fun BipeApiClient.clearTheftMark(userId: String, accessToken: String) {
    send<Unit>("POST", listOf("v1", "theft", userId, "clear"), accessToken = accessToken, response = null)
}

/** `POST /v1/theft-marks`. At most 100 IDs. The statements are not verified here: see [BipeTheftMarkVerifier]. */
suspend fun BipeApiClient.theftMarks(userIds: List<String>, openId: BipeOpenIdToken): List<BadgeEnvelope> = send(
    "POST",
    listOf("v1", "theft-marks"),
    body = encode(GuardiansRequest.serializer(), GuardiansRequest(userIds)),
    accessToken = openId.accessToken,
    serverName = openId.matrixServerName,
    response = TheftMarksResponse.serializer(),
).marks

/** A verified theft statement (docs/theft-mark.md, payload). Times are Unix seconds. */
data class BipeTheftStatement(val sub: String, val stolenAt: Long, val iat: Long, val exp: Long) {
    /** Messages from the account sent at or after this time (milliseconds) get bipe_theft_message_marker. */
    val markerStartMillis: Long get() = (stolenAt - BipeRegistries.THEFT_MARKED_MESSAGE_MINUTES * 60L) * 1000

    val expiresAtMillis: Long get() = exp * 1000
}

sealed interface BipeTheftMarkResult {
    data class Valid(val statement: BipeTheftStatement) : BipeTheftMarkResult

    /** The codes are the badge codes (docs/theft-mark.md, verification order). */
    data class Invalid(val failure: BadgeVerificationFailure) : BipeTheftMarkResult
}

/**
 * Verifies signed theft statements (bipe-shared docs/theft-mark.md, v1) with the badge keys.
 * Must match bipe-shared/test-vectors/theft-marks.json.
 *
 * @param trustedKeys `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
 * @throws IllegalArgumentException when a key is not 32 bytes.
 */
class BipeTheftMarkVerifier(trustedKeys: Map<String, ByteArray>) {
    private val verifiers: Map<String, Ed25519Verify> = trustedKeys.mapValues { (kid, key) ->
        require(key.size == Ed25519Verify.PUBLIC_KEY_LEN) { "invalid public key for kid $kid" }
        Ed25519Verify(key)
    }

    /** Decodes and verifies envelope JSON. Undecodable JSON is `malformed`. */
    fun verify(envelopeJson: String, subject: String, nowMillis: Long = System.currentTimeMillis()): BipeTheftMarkResult {
        val probe = runCatchingExceptions { json.decodeFromString<JsonObject>(envelopeJson) }.getOrNull()
        val version = (probe?.get("v") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: return BipeTheftMarkResult.Invalid(BadgeVerificationFailure.MALFORMED)
        if (version != BadgeEnvelope.SUPPORTED_VERSION) {
            return BipeTheftMarkResult.Invalid(BadgeVerificationFailure.UNSUPPORTED_VERSION)
        }
        val envelope = runCatchingExceptions { json.decodeFromJsonElement(BadgeEnvelope.serializer(), probe) }.getOrNull()
            ?: return BipeTheftMarkResult.Invalid(BadgeVerificationFailure.MALFORMED)
        return verify(envelope, subject, nowMillis)
    }

    /** Runs the documented checks in order and returns the first failure. [subject] is the account the app asked about. */
    fun verify(envelope: BadgeEnvelope, subject: String, nowMillis: Long = System.currentTimeMillis()): BipeTheftMarkResult {
        fun invalid(failure: BadgeVerificationFailure) = BipeTheftMarkResult.Invalid(failure)

        if (envelope.v != BadgeEnvelope.SUPPORTED_VERSION) return invalid(BadgeVerificationFailure.UNSUPPORTED_VERSION)
        val verifier = verifiers[envelope.kid] ?: return invalid(BadgeVerificationFailure.UNKNOWN_KID)
        val payloadBytes = Base64Url.decode(envelope.payload) ?: return invalid(BadgeVerificationFailure.MALFORMED)
        val signature = Base64Url.decode(envelope.sig) ?: return invalid(BadgeVerificationFailure.MALFORMED)
        // Verify the exact bytes. Never re-serialise the JSON before this check.
        try {
            verifier.verify(signature, payloadBytes)
        } catch (_: GeneralSecurityException) {
            return invalid(BadgeVerificationFailure.INVALID_SIGNATURE)
        }
        val payload = runCatchingExceptions {
            json.decodeFromString<JsonObject>(payloadBytes.decodeToString(throwOnInvalidSequence = true))
        }.getOrNull() ?: return invalid(BadgeVerificationFailure.MALFORMED)
        val typ = (payload["typ"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val sub = (payload["sub"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val stolenAt = payload.integer("stolen_at")
        val iat = payload.integer("iat")
        val exp = payload.integer("exp")
        if (typ != "theft" || sub == null || stolenAt == null || iat == null || exp == null) {
            return invalid(BadgeVerificationFailure.MALFORMED)
        }
        if (nowMillis >= exp * 1000) return invalid(BadgeVerificationFailure.EXPIRED)
        if (nowMillis < iat * 1000) return invalid(BadgeVerificationFailure.NOT_YET_VALID)
        if (sub != subject) return invalid(BadgeVerificationFailure.SUBJECT_MISMATCH)
        return BipeTheftMarkResult.Valid(BipeTheftStatement(sub, stolenAt, iat, exp))
    }

    /** The valid statement for [subject] among [envelopes], else null. Any error is no mark. */
    fun statement(subject: String, envelopes: List<BadgeEnvelope>, nowMillis: Long = System.currentTimeMillis()): BipeTheftStatement? =
        envelopes.firstNotNullOfOrNull { (verify(it, subject, nowMillis) as? BipeTheftMarkResult.Valid)?.statement }

    /** A JSON integer (no string, no fraction), else null. */
    private fun JsonObject.integer(key: String): Long? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.content.any { it == '.' || it == 'e' || it == 'E' }) return null
        return primitive.longOrNull
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The badge trust list from bipe-shared `registries/badge-keys.json` (ADR 0123). */
        fun bipeTrusted(): BipeTheftMarkVerifier = BipeTheftMarkVerifier(
            BipeRegistries.badgeKeys.mapValues { (kid, key) -> requireNotNull(Base64Url.decode(key)) { "invalid public key for kid $kid" } },
        )
    }
}

/** The random per-install phone ID of `PUT /v1/me/phone` (bipe-chat/bipe#268). Never derived from hardware. */
object BipePhoneId {
    internal const val KEY = "bipe.theft.phoneId"
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** The kept ID, or a new one (22 URL-safe characters) that is kept from now on. */
    fun current(preferences: SharedPreferences): String {
        preferences.getString(KEY, null)?.takeIf(::isValid)?.let { return it }
        val fresh = make()
        preferences.edit().putString(KEY, fresh).apply()
        return fresh
    }

    /** 16 to 64 URL-safe characters (contract `phone_id`). */
    fun isValid(id: String): Boolean = id.length in 16..64 && id.all { it in ALPHABET }

    internal fun make(): String {
        val random = SecureRandom()
        return String(CharArray(22) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }
}
