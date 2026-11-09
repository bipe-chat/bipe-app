// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.BoundBadgeResult
import chat.bipe.core.IssuedBadgeResult
import chat.bipe.core.ListType
import chat.bipe.core.TrustedKey
import chat.bipe.core.badgeFailureCode
import chat.bipe.core.verifyBoundSenderBadge
import chat.bipe.core.verifyIssuedBadge
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Verifies signed badge envelopes (bipe-shared/docs/badge-attestation.md, v1) through bipe-core (ADR 0267).
 * [bipeTrusted] checks against the current signed issuer registry (bipe-core `verifyIssuedBadge`, every step of the
 * verification order, bipe-chat/bipe#637). A verifier with a flat key list (tests, test-vectors/badges.json) uses
 * `verifyBoundSenderBadge`, which knows one issuer. Either way the app then reads the payload it shows.
 */
class BadgeVerifier private constructor(private val trust: Trust) {
    private sealed interface Trust {
        class Keys(val keys: Map<String, ByteArray>) : Trust
        class Registry(val json: String) : Trust
    }

    /**
     * @param trustedKeys `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
     * @throws IllegalArgumentException when a key is not 32 bytes.
     */
    constructor(trustedKeys: Map<String, ByteArray>) : this(
        Trust.Keys(trustedKeys.onEach { (kid, key) -> require(key.size == PUBLIC_KEY_BYTES) { "invalid public key for kid $kid" } }),
    )

    /** @param issuerRegistryJson the payload JSON of a valid issuer registry (`VerifiedList.payloadJson`). */
    constructor(issuerRegistryJson: String) : this(Trust.Registry(issuerRegistryJson))

    /**
     * Verifies envelope JSON and returns the first failure of the verification order.
     * `senderMasterKey` is the sender's cross-signing master key from the SDK (`UserIdentity.masterKey()`), null when unknown.
     */
    fun verify(
        envelopeJson: String,
        sender: String,
        senderMasterKey: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): BadgeVerificationResult {
        val now = Math.floorDiv(nowMillis, 1000L)
        return when (trust) {
            is Trust.Keys -> {
                val keys = trust.keys.map { (kid, key) -> TrustedKey(kid, key) }
                when (val result = verifyBoundSenderBadge(envelopeJson, sender, senderMasterKey, keys, now)) {
                    is BoundBadgeResult.Valid -> shown(envelopeJson, issuerName = null)
                    is BoundBadgeResult.Invalid -> invalid(badgeFailureCode(result.failure))
                }
            }
            is Trust.Registry -> when (
                val result = verifyIssuedBadge(envelopeJson, sender, senderMasterKey, trust.json, BipeRegistries.BADGE_LIFE_DAYS.toUInt(), now)
            ) {
                is IssuedBadgeResult.Valid -> shown(envelopeJson, issuerName = result.badge.issuerName)
                is IssuedBadgeResult.Invalid -> invalid(badgeFailureCode(result.failure))
            }
        }
    }

    /** The envelope as the server sends it. Its `payload` and `sig` are the exact base64url text, so re-encoding is safe. */
    fun verify(
        envelope: BadgeEnvelope,
        sender: String,
        senderMasterKey: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): BadgeVerificationResult = verify(json.encodeToString(BadgeEnvelope.serializer(), envelope), sender, senderMasterKey, nowMillis)

    companion object {
        private const val PUBLIC_KEY_BYTES = 32
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The release trust: the newest valid issuer registry, bundled or fetched ([SignedLists], bipe-chat/bipe#637).
         * An app build always bundles one; the flat keys of `registries/badge-keys.json` are the last fallback.
         */
        fun bipeTrusted(): BadgeVerifier = SignedLists.shared.current(ListType.ISSUERS)?.payloadJson?.let(::BadgeVerifier)
            ?: BadgeVerifier(BipeRegistries.badgeKeys.mapValues { (kid, key) -> requireNotNull(Base64Url.decode(key)) { "invalid public key for kid $kid" } })

        private fun invalid(code: String) = BadgeVerificationResult.Invalid(
            BadgeVerificationFailure.entries.firstOrNull { it.code == code } ?: BadgeVerificationFailure.MALFORMED,
        )

        /**
         * The payload of an envelope that bipe-core accepted, as the app shows it. A level the app cannot show yet
         * (`person`, `org`, ADR 0272) is no seal: `malformed`, fail closed.
         */
        private fun shown(envelopeJson: String, issuerName: String?): BadgeVerificationResult {
            val payload = runCatchingExceptions {
                val envelope = json.decodeFromString(BadgeEnvelope.serializer(), envelopeJson)
                val bytes = requireNotNull(Base64Url.decode(envelope.payload))
                val fields = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                json.decodeFromJsonElement(BadgePayload.serializer(), fields).copy(issuerName = issuerName)
            }.getOrNull()?.takeIf { it.isWellFormed() } ?: return BadgeVerificationResult.Invalid(BadgeVerificationFailure.MALFORMED)
            return BadgeVerificationResult.Valid(payload)
        }
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
    /** The account's cross-signing master key the badge is bound to. Every level (ADR 0239). */
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
    /** `institution` only, optional: the verified link domains (ADR 0036), as sent. Read through [domainList]. */
    @SerialName("domains") val domainsField: JsonElement? = null,
    /** Issued at, Unix seconds. */
    val iat: Long,
    /** Expires at, Unix seconds. */
    val exp: Long,
    /** The issuer ID (`iss`). Absent means `bipe` (docs/badge-attestation.md "Issuer"). */
    @SerialName("iss") val issuer: String = "bipe",
    /** The issuer `name` from the signed issuer registry; null when a flat key list checked the badge. */
    @Transient val issuerName: String? = null,
) {
    /** The required fields of [lvl] (badge-attestation.md, step 4). */
    internal fun isWellFormed(): Boolean = !msk.isNullOrEmpty() && iat in BADGE_TIME_RANGE && exp in BADGE_TIME_RANGE && when (lvl) {
        BadgeLevel.CNPJ -> cnpj != null && CNPJ_PATTERN.matches(cnpj) && !legalName.isNullOrEmpty() &&
            method != null && checkedMonth != null && MONTH_PATTERN.matches(checkedMonth)
        BadgeLevel.INSTITUTION -> cnpj != null && CNPJ_PATTERN.matches(cnpj) && domainList() != null
        BadgeLevel.BRAND_MARK -> cnpj != null && CNPJ_PATTERN.matches(cnpj) && name.isNotEmpty() &&
            !mark.isNullOrEmpty() && !brand.isNullOrEmpty() && label != null && label in BipeRegistries.BRAND_MARK_LABELS
        BadgeLevel.PIX, BadgeLevel.GOVBR -> true
    }

    /** `institution`: the verified domains, empty when absent; null when the field is not a list of domains. */
    internal fun domainList(): List<String>? = when (val field = domainsField) {
        null, JsonNull -> emptyList()
        is JsonArray -> field.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(BipeNoticeLinks::isDomain) ?: return null
        }
        else -> null
    }

    /** `institution` only: the verified link domains. Empty: no link in a notice is clickable. */
    val domains: List<String> get() = if (lvl == BadgeLevel.INSTITUTION) domainList().orEmpty() else emptyList()
}

/** badge-attestation.md "Parser rules". */
private val BADGE_TIME_RANGE = 0L..253_402_300_799L

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
    MASTER_KEY_MISMATCH("master_key_mismatch"),

    // Steps 2, 6 to 9 and 12: only a check against the issuer registry gives these (bipe-chat/bipe#637).
    UNKNOWN_ISSUER("unknown_issuer"),
    ISSUER_MISMATCH("issuer_mismatch"),
    LEVEL_NOT_ALLOWED("level_not_allowed"),
    UNKNOWN_SCHEME("unknown_scheme"),
    SCHEME_NOT_ALLOWED("scheme_not_allowed"),
    SCHEME_MISMATCH("scheme_mismatch"),
    LIFE_TOO_LONG("life_too_long"),
    REGION_MISMATCH("region_mismatch"),
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
