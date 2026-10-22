// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import android.os.Build
import chat.bipe.shared.api.BipeBadgeGoneReason
import chat.bipe.shared.api.BipeBadgeLookup
import chat.bipe.shared.generated.BipeRegistries
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

// Badges and company seals, written from bipe-shared docs/badge-attestation.md (format v1, verification order),
// ADR 0042 and ADR 0123. Cases: test-vectors/badges.json. Fail closed: anything but Valid means no badge.

/** Contract `BadgeEnvelope`. */
@Serializable
data class BipeBadgeEnvelope(
    @SerialName("v") val v: Int,
    @SerialName("kid") val kid: String,
    /** base64url JSON payload bytes. */
    @SerialName("payload") val payload: String,
    /** base64url Ed25519 signature over the decoded payload bytes. */
    @SerialName("sig") val sig: String,
)

/** The signed statement inside an envelope. */
data class BipeBadgePayload(
    val sub: String,
    val lvl: Level,
    /** For `cnpj`: the trade name when the registry has one, else the legal name. */
    val name: String,
    val iat: Instant,
    val exp: Instant,
    val dev: String?,
    val msk: String?,
    val cnpj: String?,
    val legalName: String?,
    val tradeName: String?,
    val method: String?,
    /** `YYYY-MM`: the Receita Federal release that last showed the CNPJ as active. */
    val checkedMonth: String?,
) {
    enum class Level(val id: String) { PIX("pix"), GOVBR("govbr"), INSTITUTION("institution"), CNPJ("cnpj") }
}

/** Result ids of docs/badge-attestation.md. */
sealed class BipeBadgeResult(val id: String) {
    data class Valid(val payload: BipeBadgePayload) : BipeBadgeResult("valid")
    data object UnsupportedVersion : BipeBadgeResult("unsupported_version")
    data object UnknownKid : BipeBadgeResult("unknown_kid")
    data object InvalidSignature : BipeBadgeResult("invalid_signature")
    data object Malformed : BipeBadgeResult("malformed")
    data object Expired : BipeBadgeResult("expired")
    data object NotYetValid : BipeBadgeResult("not_yet_valid")
    data object SubjectMismatch : BipeBadgeResult("subject_mismatch")
    data object MasterKeyMismatch : BipeBadgeResult("master_key_mismatch")
    data object DeviceMismatch : BipeBadgeResult("device_mismatch")
}

object BadgeVerifier {
    /** X.509 SubjectPublicKeyInfo prefix of a raw Ed25519 public key. */
    private val ed25519Prefix = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
    private val cnpjShape = Regex("^[0-9A-Z]{12}[0-9]{2}$")
    private val monthShape = Regex("^[0-9]{4}-(0[1-9]|1[0-2])$")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Verifies [envelope] in the order of docs/badge-attestation.md. [senderMasterKey] is the sender's cross-signing
     * master key from the SDK (`UserIdentity.masterKey()`). A release build passes [BipeRegistries.badgeKeys] only.
     * Ed25519 comes from the platform provider (Android 13 and later); on older phones every badge fails closed.
     */
    fun verify(
        envelope: BipeBadgeEnvelope,
        sender: String,
        senderDevice: String? = null,
        senderMasterKey: String?,
        now: Instant = Instant.now(),
        keys: Map<String, String> = BipeRegistries.badgeKeys,
    ): BipeBadgeResult {
        if (envelope.v != 1) return BipeBadgeResult.UnsupportedVersion
        val rawKey = keys[envelope.kid]?.let(::base64UrlDecode)
        if (rawKey == null || rawKey.size != 32) return BipeBadgeResult.UnknownKid
        val payloadBytes = base64UrlDecode(envelope.payload) ?: return BipeBadgeResult.InvalidSignature
        val signature = base64UrlDecode(envelope.sig) ?: return BipeBadgeResult.InvalidSignature
        if (!signatureIsValid(rawKey, payloadBytes, signature)) return BipeBadgeResult.InvalidSignature
        val payload = parse(payloadBytes) ?: return BipeBadgeResult.Malformed
        if (now.isBefore(payload.iat)) return BipeBadgeResult.NotYetValid
        if (!now.isBefore(payload.exp)) return BipeBadgeResult.Expired
        if (payload.sub != sender) return BipeBadgeResult.SubjectMismatch
        when (payload.lvl) {
            BipeBadgePayload.Level.CNPJ ->
                if (senderMasterKey == null || payload.msk != senderMasterKey) return BipeBadgeResult.MasterKeyMismatch
            else ->
                if (senderDevice == null || payload.dev != senderDevice) return BipeBadgeResult.DeviceMismatch
        }
        return BipeBadgeResult.Valid(payload)
    }

    /** Unpadded or padded base64url. */
    fun base64UrlDecode(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value.trimEnd('='))
    }.getOrNull()

    private fun signatureIsValid(rawKey: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(ed25519Prefix + rawKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
    }

    private fun parse(bytes: ByteArray): BipeBadgePayload? {
        val obj = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return null
        val sub = obj.string("sub")?.takeIf { it.isNotEmpty() } ?: return null
        val lvl = obj.string("lvl")?.let { id -> BipeBadgePayload.Level.entries.firstOrNull { it.id == id } } ?: return null
        val name = obj.string("name")?.takeIf { it.isNotEmpty() } ?: return null
        val iat = obj.integer("iat") ?: return null
        val exp = obj.integer("exp") ?: return null
        val dev = obj.string("dev")?.takeIf { it.isNotEmpty() }
        val msk = obj.string("msk")?.takeIf { it.isNotEmpty() }
        val cnpj = obj.string("cnpj")
        val legalName = obj.string("legal_name")
        val method = obj.string("method")
        val checkedMonth = obj.string("checked_month")
        if (lvl == BipeBadgePayload.Level.CNPJ) {
            if (cnpj == null || !cnpjShape.matches(cnpj)) return null
            if (legalName.isNullOrEmpty() || msk == null) return null
            if (method !in setOf("ecnpj", "partner", "staff")) return null
            if (checkedMonth == null || !monthShape.matches(checkedMonth)) return null
        } else if (dev == null) {
            return null
        }
        return BipeBadgePayload(
            sub = sub,
            lvl = lvl,
            name = name,
            iat = Instant.ofEpochSecond(iat),
            exp = Instant.ofEpochSecond(exp),
            dev = dev,
            msk = msk,
            cnpj = cnpj,
            legalName = legalName,
            tradeName = obj.string("trade_name")?.takeIf { it.isNotEmpty() },
            method = method,
            checkedMonth = checkedMonth,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.integer(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        val value = primitive.doubleOrNull ?: return null
        return if (value == Math.rint(value)) value.toLong() else null
    }
}

/** The seal of an account. Only [Verified] shows a seal; anything else shows none (fail closed). */
sealed interface BipeCompanySealState {
    /** A valid `cnpj` badge bound to the account's master key. */
    data class Verified(val payload: BipeBadgePayload) : BipeCompanySealState

    /** `GET /v1/badges` answered 410. */
    data class Revoked(val reason: BipeBadgeGoneReason) : BipeCompanySealState

    /** The badge no longer binds to the account's master key (for example after a key reset). */
    data object Suspended : BipeCompanySealState

    /** No badge, or one that does not verify. */
    data object NoSeal : BipeCompanySealState

    /** The notice for a seal that is no longer valid, else `null`. */
    fun notice(context: Context): String? = when (this) {
        is Revoked -> when (reason) {
            BipeBadgeGoneReason.NOT_ACTIVE, BipeBadgeGoneReason.NOT_FOUND -> context.getString(R.string.bipe_badge_company_revoked_not_active)
            else -> context.getString(R.string.bipe_badge_company_revoked)
        }
        Suspended -> context.getString(R.string.bipe_badge_company_suspended)
        is Verified, NoSeal -> null
    }
}

object BipeCompanySeals {
    /** The seal state from a badge lookup. [masterKey] is the account's master key from the SDK, `null` when unknown. */
    fun state(
        lookup: BipeBadgeLookup,
        userId: String,
        masterKey: String?,
        now: Instant = Instant.now(),
        keys: Map<String, String> = BipeRegistries.badgeKeys,
    ): BipeCompanySealState = when (lookup) {
        BipeBadgeLookup.NoBadge -> BipeCompanySealState.NoSeal
        is BipeBadgeLookup.Gone -> BipeCompanySealState.Revoked(lookup.reason)
        is BipeBadgeLookup.Envelope -> when (val result = BadgeVerifier.verify(lookup.envelope, userId, senderMasterKey = masterKey, now = now, keys = keys)) {
            is BipeBadgeResult.Valid ->
                if (result.payload.lvl == BipeBadgePayload.Level.CNPJ) BipeCompanySealState.Verified(result.payload) else BipeCompanySealState.NoSeal
            BipeBadgeResult.MasterKeyMismatch -> BipeCompanySealState.Suspended
            else -> BipeCompanySealState.NoSeal
        }
    }

    /** `checked_month` (`YYYY-MM`) as the first day of that month. */
    fun checkedDate(payload: BipeBadgePayload): LocalDate? =
        payload.checkedMonth?.let { runCatching { LocalDate.parse("$it-01") }.getOrNull() }
}
