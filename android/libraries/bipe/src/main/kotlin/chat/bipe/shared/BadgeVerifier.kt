// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.crypto.tink.subtle.Ed25519Verify
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.security.GeneralSecurityException

/**
 * Verifies signed badge envelopes (bipe-shared/docs/badge-attestation.md, v1).
 * Must match bipe-shared/test-vectors/badges.json.
 *
 * @param trustedKeys `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
 * @throws IllegalArgumentException when a key is not 32 bytes.
 */
class BadgeVerifier(trustedKeys: Map<String, ByteArray>) {
    private val verifiers: Map<String, Ed25519Verify> = trustedKeys.mapValues { (kid, key) ->
        require(key.size == Ed25519Verify.PUBLIC_KEY_LEN) { "invalid public key for kid $kid" }
        Ed25519Verify(key)
    }

    /**
     * Decodes and verifies envelope JSON. Undecodable JSON is `malformed`.
     * `senderMasterKey` is the sender's cross-signing master key from the SDK (`UserIdentity.masterKey()`), null when unknown.
     */
    fun verify(
        envelopeJson: String,
        sender: String,
        senderDevice: String,
        senderMasterKey: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): BadgeVerificationResult {
        // Check `v` before the full shape: a future version may have other fields.
        val probe = runCatchingExceptions { json.decodeFromString<JsonObject>(envelopeJson) }.getOrNull()
        val version = (probe?.get("v") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: return BadgeVerificationResult.Invalid(BadgeVerificationFailure.MALFORMED)
        if (version != BadgeEnvelope.SUPPORTED_VERSION) {
            return BadgeVerificationResult.Invalid(BadgeVerificationFailure.UNSUPPORTED_VERSION)
        }
        val envelope = runCatchingExceptions { json.decodeFromJsonElement(BadgeEnvelope.serializer(), probe) }.getOrNull()
            ?: return BadgeVerificationResult.Invalid(BadgeVerificationFailure.MALFORMED)
        return verify(envelope, sender, senderDevice, senderMasterKey, nowMillis)
    }

    /** Runs the documented checks in order and returns the first failure. */
    fun verify(
        envelope: BadgeEnvelope,
        sender: String,
        senderDevice: String,
        senderMasterKey: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): BadgeVerificationResult {
        fun invalid(failure: BadgeVerificationFailure) = BadgeVerificationResult.Invalid(failure)

        if (envelope.v != BadgeEnvelope.SUPPORTED_VERSION) return invalid(BadgeVerificationFailure.UNSUPPORTED_VERSION)
        val verifier = verifiers[envelope.kid] ?: return invalid(BadgeVerificationFailure.UNKNOWN_KID)
        // Undecodable base64url is not a signature over any payload bytes, so it fails closed as malformed.
        val payloadBytes = Base64Url.decode(envelope.payload) ?: return invalid(BadgeVerificationFailure.MALFORMED)
        val signature = Base64Url.decode(envelope.sig) ?: return invalid(BadgeVerificationFailure.MALFORMED)
        // Verify the exact bytes. Never re-serialise the JSON before this check.
        try {
            verifier.verify(signature, payloadBytes)
        } catch (_: GeneralSecurityException) {
            return invalid(BadgeVerificationFailure.INVALID_SIGNATURE)
        }
        val payload = runCatchingExceptions { json.decodeFromString(BadgePayload.serializer(), payloadBytes.decodeToString(throwOnInvalidSequence = true)) }
            .getOrNull()
            ?.takeIf { it.isWellFormed() }
            ?: return invalid(BadgeVerificationFailure.MALFORMED)

        if (nowMillis >= payload.exp * 1000) return invalid(BadgeVerificationFailure.EXPIRED)
        if (nowMillis < payload.iat * 1000) return invalid(BadgeVerificationFailure.NOT_YET_VALID)
        if (payload.sub != sender) return invalid(BadgeVerificationFailure.SUBJECT_MISMATCH)
        if (payload.lvl == BadgeLevel.CNPJ || payload.lvl == BadgeLevel.BRAND_MARK) {
            // A company badge or a brand mark binds to the account, so any of its devices may send. No known master key fails closed.
            if (senderMasterKey == null || payload.msk != senderMasterKey) return invalid(BadgeVerificationFailure.MASTER_KEY_MISMATCH)
        } else if (payload.dev != senderDevice) {
            return invalid(BadgeVerificationFailure.DEVICE_MISMATCH)
        }
        return BadgeVerificationResult.Valid(payload)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The release trust list from bipe-shared `registries/badge-keys.json` (ADR 0123). */
        fun bipeTrusted(): BadgeVerifier = BadgeVerifier(
            BipeRegistries.badgeKeys.mapValues { (kid, key) -> requireNotNull(Base64Url.decode(key)) { "invalid public key for kid $kid" } },
        )
    }
}

@Serializable
data class BadgeEnvelope(
    val v: Int,
    val kid: String,
    /** base64url without padding: the UTF-8 JSON payload bytes. */
    val payload: String,
    /** base64url without padding: Ed25519 signature over the decoded payload bytes. */
    val sig: String,
) {
    companion object {
        const val SUPPORTED_VERSION = 1
    }
}

@Serializable
enum class BadgeLevel {
    @SerialName("pix") PIX,
    @SerialName("govbr") GOVBR,
    @SerialName("institution") INSTITUTION,

    /** A verified company (ADR 0042), bound to the account's cross-signing master key. */
    @SerialName("cnpj") CNPJ,

    /** A brand's mark on a reseller's work account (ADR 0162), bound to the reseller's master key. */
    @SerialName("brand-mark") BRAND_MARK,
}

/** How a company proved control of its CNPJ (ADR 0042). */
@Serializable
enum class BadgeCnpjMethod {
    @SerialName("ecnpj") ECNPJ,
    @SerialName("partner") PARTNER,
    @SerialName("staff") STAFF,
}

@Serializable
data class BadgePayload(
    /** Matrix user ID. */
    val sub: String,
    val lvl: BadgeLevel,
    /** Verified name. For `cnpj`: the trade name when the registry has one, else the legal name. */
    val name: String,
    /** Normalised CNPJ (14 characters, digits and uppercase letters). Present for `institution` and `cnpj`. */
    val cnpj: String? = null,
    /** Fingerprint of the device key the badge is bound to. `pix`, `govbr` and `institution` only. */
    val dev: String? = null,
    /** The account's cross-signing master key the badge is bound to. `cnpj` and `brand-mark` only. */
    val msk: String? = null,
    /** `brand-mark` only: the mark ID, the brand's Matrix user ID and the label id of registries/brand-marks.json. */
    val mark: String? = null,
    val brand: String? = null,
    val label: String? = null,
    @SerialName("legal_name") val legalName: String? = null,
    @SerialName("trade_name") val tradeName: String? = null,
    val method: BadgeCnpjMethod? = null,
    /** The Receita Federal release that last showed the CNPJ as active, YYYY-MM. */
    @SerialName("checked_month") val checkedMonth: String? = null,
    /** `cnpj` only, optional: the CVM register line (ADR 0077), as sent. BipeCvmLines reads it leniently. */
    val cvm: JsonElement? = null,
    /** Issued at, Unix seconds. */
    val iat: Long,
    /** Expires at, Unix seconds. */
    val exp: Long,
) {
    /** The required fields of [lvl] (badge-attestation.md, step 4). */
    internal fun isWellFormed(): Boolean = when (lvl) {
        BadgeLevel.CNPJ -> cnpj != null && CNPJ_PATTERN.matches(cnpj) && !legalName.isNullOrEmpty() && !msk.isNullOrEmpty() &&
            method != null && checkedMonth != null && MONTH_PATTERN.matches(checkedMonth)
        BadgeLevel.BRAND_MARK -> cnpj != null && CNPJ_PATTERN.matches(cnpj) && !msk.isNullOrEmpty() && dev == null && name.isNotEmpty() &&
            !mark.isNullOrEmpty() && !brand.isNullOrEmpty() && label != null && label in BipeRegistries.BRAND_MARK_LABELS
        BadgeLevel.INSTITUTION -> cnpj != null && dev != null
        BadgeLevel.PIX, BadgeLevel.GOVBR -> dev != null
    }
}

private val CNPJ_PATTERN = Regex("[0-9A-Z]{12}[0-9]{2}")
private val MONTH_PATTERN = Regex("[0-9]{4}-(0[1-9]|1[0-2])")

sealed interface BadgeVerificationResult {
    data class Valid(val payload: BadgePayload) : BadgeVerificationResult
    data class Invalid(val failure: BadgeVerificationFailure) : BadgeVerificationResult
}

/** [code] values are the codes in badge-attestation.md and test-vectors/badges.json. */
enum class BadgeVerificationFailure(val code: String) {
    UNSUPPORTED_VERSION("unsupported_version"),
    UNKNOWN_KID("unknown_kid"),
    INVALID_SIGNATURE("invalid_signature"),
    MALFORMED("malformed"),
    EXPIRED("expired"),
    NOT_YET_VALID("not_yet_valid"),
    SUBJECT_MISMATCH("subject_mismatch"),
    DEVICE_MISMATCH("device_mismatch"),
    MASTER_KEY_MISMATCH("master_key_mismatch"),
}

/**
 * Strict base64url without padding (RFC 4648 section 5). Rejects padding and the standard alphabet.
 * Own decoder because `java.util.Base64` needs API 26 and min SDK is 24.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun decode(input: String): ByteArray? {
        if (input.length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(input.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in input) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            // Keep only the bits not yet written, so the buffer cannot overflow.
            buffer = buffer shl 6 or value and 0xFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer shr bits and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
