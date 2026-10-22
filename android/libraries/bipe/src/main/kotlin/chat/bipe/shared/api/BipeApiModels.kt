// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.BadgeEnvelope
import chat.bipe.shared.BipeHandleProblem
import chat.bipe.shared.runCatchingExceptions
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime

// Models of bipe-shared/api/openapi.yaml. JSON keys are snake_case.

@Serializable
data class BipeSignupRequest(
    /** Optional while the server runs open sign-up (beta). */
    @SerialName("invite_code") val inviteCode: String? = null,
    /** Device-first: optional recovery fallback, never a login. */
    @SerialName("email") val email: String? = null,
    /** Legacy beta sign-up only. Absent: device-first, the server makes the password (ADR 0057). The app sends no password. */
    @SerialName("password") val password: String? = null,
    /** Device-first, optional: the PIN, set at once. Must pass [chat.bipe.shared.account.BipePin.problem]. */
    @SerialName("pin") val pin: String? = null,
    /** Language of the emails to the recovery email: `pt` or `en`. */
    @SerialName("lang") val lang: String? = null,
    @SerialName("handle") val handle: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    /** Same key on a retry: the same account (ADR 0094). From the pending sign-up in the vault. */
    @SerialName("signup_key") val signupKey: String? = null,
    /** The legal version the user ticked at sign-up (ADR 0113): `BipeRegistries.LEGAL_VERSION`, YYYY-MM-DD. */
    @SerialName("terms_version") val termsVersion: String? = null,
    /** ADR 0087: `company` needs an attestation (ADR 0043). Android has none yet (#92), so it never sends `company`. */
    @SerialName("account_type") val accountType: String? = null,
) {
    /** Never prints the PIN, the password, the email or the key. */
    override fun toString(): String = "BipeSignupRequest"
}

/** The server's sign-up mode. An app that cannot read it assumes [inviteRequired] is `true`. */
@Serializable
data class BipeSignupConfig(
    @SerialName("invite_required") val inviteRequired: Boolean,
    /** The current legal version (ADR 0113). `null` from a server older than the clickwrap. */
    @SerialName("terms_version") val termsVersion: String? = null,
)

@Serializable
data class BipeSignupResumeRequest(
    @SerialName("signup_key") val signupKey: String,
) {
    override fun toString(): String = "BipeSignupResumeRequest"
}

@Serializable
data class BipeSignupResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("handle") val handle: String? = null,
    /** Present when a personal invite was used. */
    @SerialName("inviter_user_id") val inviterUserId: String? = null,
    /** Device-first only. The hidden password, sent once. Keep it on this device only; never show it. */
    @SerialName("password") val password: String? = null,
    /** Device-first only. Sent once. Synced through Block Store. */
    @SerialName("recovery_token") val recoveryToken: String? = null,
) {
    /** Never prints the password or the token. */
    override fun toString(): String = "BipeSignupResponse($userId)"
}

@Serializable
data class BipeInvitePreview(
    @SerialName("valid") val valid: Boolean,
    /** Without @. Only for a valid personal invite whose inviter has a handle. */
    @SerialName("inviter_handle") val inviterHandle: String? = null,
)

enum class BipeInviteStatus(val id: String) {
    PENDING("pending"),
    USED("used"),
    EXPIRED("expired"),
    REVOKED("revoked"),
}

@Serializable
data class BipeInvite(
    @SerialName("id") val id: String,
    @SerialName("status") private val statusId: String,
    @Serializable(with = InstantSerializer::class) @SerialName("created_at") val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
    /** Only when [status] is `USED`. */
    @SerialName("joined_user_id") val joinedUserId: String? = null,
    /** The name the user gave the invite. Only they see it. */
    @SerialName("name") val name: String? = null,
) {
    /** An unknown status from a newer server must not fail the whole list. */
    val status: BipeInviteStatus
        get() = BipeInviteStatus.entries.firstOrNull { it.id == statusId } ?: BipeInviteStatus.EXPIRED
}

@Serializable
data class BipeMyInvites(
    @SerialName("quota") val quota: Int,
    @SerialName("available") val available: Int,
    /** Only when [available] is 0: when the next slot comes back. */
    @Serializable(with = InstantSerializer::class) @SerialName("next_available_at") val nextAvailableAt: Instant? = null,
    @SerialName("invites") val invites: List<BipeInvite>,
)

/** `GET /v1/me/invite-connections`. */
@Serializable
data class BipeInviteConnections(
    @SerialName("user_ids") val userIds: List<String>,
)

@Serializable
data class BipeCreatedInvite(
    @SerialName("id") val id: String,
    @SerialName("code") val code: String,
    @SerialName("url") val url: String,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
) {
    /** Never prints the code. */
    override fun toString(): String = "BipeCreatedInvite($id)"
}

@Serializable
data class BipeContactMatch(
    /** E.164, as normalised by the server. */
    @SerialName("phone") val phone: String,
    @SerialName("user_id") val userId: String,
) {
    /** Never prints the number. */
    override fun toString(): String = "BipeContactMatch($userId)"
}

@Serializable
data class BipeHandleAvailability(
    @SerialName("handle") val handle: String,
    @SerialName("available") val available: Boolean,
    @SerialName("reason") private val reason: String? = null,
) {
    /** An unknown reason from a newer server must not fail the whole response. */
    val problem: BipeHandleProblem?
        get() = reason?.let { BipeHandleProblem.fromId(it) ?: BipeHandleProblem.TAKEN }
}

@Serializable
data class BipeHandleRecord(
    @SerialName("handle") val handle: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class BipeMyHandle(
    @SerialName("user_id") val userId: String,
    @SerialName("handle") val handle: String? = null,
    /** `null` when a change is allowed now. */
    @Serializable(with = InstantSerializer::class) @SerialName("changeable_at") val changeableAt: Instant? = null,
)

/** Contract: `BusinessClaim` (ADR 0042). Only the parts the trust card reads. */
@Serializable
data class BipeBusinessClaim(
    /** `registry_pending`, `awaiting_proof`, `verified`, `rejected` or `revoked`. */
    @SerialName("status") val status: String,
    @SerialName("domains") val domains: List<BipeBusinessDomain> = emptyList(),
)

@Serializable
data class BipeBusinessDomain(
    @SerialName("domain") val domain: String,
    @Serializable(with = InstantSerializer::class) @SerialName("verified_at") val verifiedAt: Instant? = null,
)

/** `GET /v1/profiles/{handle}`: a published profile (ADR 0041). Never carries the Matrix ID. */
@Serializable
data class BipePublicProfile(
    @SerialName("handle") val handle: String,
    @SerialName("name") val name: String,
    /** Plain text. `null` when empty. */
    @SerialName("bio") val bio: String? = null,
    /** Absolute URL of the photo, with a version. `null` without a photo. */
    @SerialName("photo_url") val photoUrl: String? = null,
    /** Set by the server only. The apps do not show it from the profile yet. */
    @SerialName("badge") val badge: BadgeEnvelope? = null,
)

/** `GET /v1/me/profile`: the caller's profile, published or not. A user who never saved one gets `enabled` false and an empty name. */
@Serializable
data class BipeMyProfile(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String,
    @SerialName("has_photo") val hasPhoto: Boolean,
    /** `null` when the caller has no handle. */
    @SerialName("handle") val handle: String? = null,
    /** `null` without a photo or without a handle. */
    @SerialName("photo_url") val photoUrl: String? = null,
)

/** `PUT /v1/me/profile`. */
@Serializable
data class BipeProfileUpdate(
    @SerialName("enabled") val enabled: Boolean,
    @SerialName("name") val name: String,
    @SerialName("bio") val bio: String,
)

// Device-first accounts (ADR 0057, ADR 0059)

/** `Credentials`: sent once. The password stays on this device; the token goes to Block Store. */
@Serializable
data class BipeCredentials(
    @SerialName("user_id") val userId: String,
    @SerialName("password") val password: String,
    @SerialName("recovery_token") val recoveryToken: String,
) {
    override fun toString(): String = "BipeCredentials($userId)"
}

enum class BipeRecoveryMethod(val id: String) { TOKEN("token"), EMAIL("email"), GUARDIAN("guardian"), CPF("cpf") }

enum class BipeRecoveryPurpose(val id: String) { ACCESS("access"), PIN_RESET("pin_reset") }

enum class BipeRecoveryState(val id: String) { WAITING("waiting"), COMPLETED("completed"), CANCELLED("cancelled"), EXPIRED("expired") }

/** `Recovery`: a recovery of the caller's account that is running or ended in the last 7 days. */
@Serializable
data class BipeRecovery(
    @SerialName("id") val id: String,
    @SerialName("method") private val methodId: String,
    @SerialName("purpose") private val purposeId: String,
    @SerialName("state") private val stateId: String,
    @Serializable(with = InstantSerializer::class) @SerialName("created_at") val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) @SerialName("ended_at") val endedAt: Instant? = null,
) {
    /** `null` for a method from a newer server. */
    val method: BipeRecoveryMethod? get() = BipeRecoveryMethod.entries.firstOrNull { it.id == methodId }
    val purpose: BipeRecoveryPurpose? get() = BipeRecoveryPurpose.entries.firstOrNull { it.id == purposeId }
    val state: BipeRecoveryState? get() = BipeRecoveryState.entries.firstOrNull { it.id == stateId }
}

/** `AccountStatus`: `GET /v1/me/account`. */
@Serializable
data class BipeAccountStatus(
    @SerialName("user_id") val userId: String,
    @SerialName("pin_set") val pinSet: Boolean,
    @SerialName("pin_blocked") val pinBlocked: Boolean,
    @SerialName("pin_tries_left") val pinTriesLeft: Int,
    /** False for a beta account that still signs in with its own password. */
    @SerialName("device_first") val deviceFirst: Boolean,
    @SerialName("recovery_token_set") val recoveryTokenSet: Boolean,
    @SerialName("email") val email: String? = null,
    @SerialName("email_pending") val emailPending: String? = null,
    /** Newest first. */
    @SerialName("recoveries") val recoveries: List<BipeRecovery> = emptyList(),
    /** The last legal version this account accepted (ADR 0113). `null`: none recorded. */
    @SerialName("terms_version") val termsVersion: String? = null,
    /** Personal accounts one phone may hold (ADR 0086). `null` from an older server: use the registry value. */
    @SerialName("max_accounts") val maxAccounts: Int? = null,
    /** ADR 0087: `personal` or `company`. `null` from an older server: personal. */
    @SerialName("account_type") val accountType: String? = null,
) {
    override fun toString(): String = "BipeAccountStatus($userId, deviceFirst=$deviceFirst, pinSet=$pinSet, pinBlocked=$pinBlocked)"
}

/** `GET /v1/accounts/{userId}/type` (ADR 0132). `personal` or `company`; the type comes from sign-up, never from the profile. */
@Serializable
data class BipeAccountTypeResponse(
    @SerialName("account_type") val accountType: String,
)

/** `RecoveryStarted`: the claim secret is sent once. Keep it on the new device. */
@Serializable
data class BipeRecoveryStarted(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("claim_secret") val claimSecret: String,
    @SerialName("purpose") val purposeId: String,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant,
) {
    val purpose: BipeRecoveryPurpose? get() = BipeRecoveryPurpose.entries.firstOrNull { it.id == purposeId }

    override fun toString(): String = "BipeRecoveryStarted($recoveryId)"
}

/** `RecoveryClaim`: the credentials are present only when this call completed an access recovery. */
@Serializable
data class BipeRecoveryClaim(
    @SerialName("state") private val stateId: String,
    @SerialName("purpose") private val purposeId: String,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("recovery_token") val recoveryToken: String? = null,
) {
    val state: BipeRecoveryState? get() = BipeRecoveryState.entries.firstOrNull { it.id == stateId }
    val purpose: BipeRecoveryPurpose? get() = BipeRecoveryPurpose.entries.firstOrNull { it.id == purposeId }

    val credentials: BipeCredentials?
        get() = if (userId != null && password != null && recoveryToken != null) BipeCredentials(userId, password, recoveryToken) else null

    override fun toString(): String = "BipeRecoveryClaim($stateId, $purposeId)"
}

/** `HandleChange` (ADR 0066). No [old]: a first set. No [new]: a removal. */
@Serializable
data class BipeHandleChange(
    @SerialName("user_id") val userId: String,
    @SerialName("old") val old: String? = null,
    @SerialName("new") val new: String? = null,
    @Serializable(with = InstantSerializer::class) @SerialName("at") val at: Instant,
)

@Serializable
internal data class PinRequest(@SerialName("pin") val pin: String) {
    override fun toString(): String = "PinRequest"
}

@Serializable
internal data class TokenRecoveryRequest(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("pin") val pin: String,
) {
    override fun toString(): String = "TokenRecoveryRequest"
}

@Serializable
internal data class EmailRecoveryRequest(@SerialName("email") val email: String) {
    override fun toString(): String = "EmailRecoveryRequest"
}

/** `PUT /v1/me/email`. [lang] is `pt` or `en`. */
@Serializable
internal data class EmailRequest(@SerialName("email") val email: String, @SerialName("lang") val lang: String) {
    override fun toString(): String = "EmailRequest"
}

/** Exactly one of [pin] and [newPin]. */
@Serializable
internal data class EmailRecoveryConfirmRequest(
    @SerialName("token") val token: String,
    @SerialName("pin") val pin: String? = null,
    @SerialName("new_pin") val newPin: String? = null,
) {
    override fun toString(): String = "EmailRecoveryConfirmRequest"
}

@Serializable
internal data class ClaimRequest(@SerialName("claim_secret") val claimSecret: String) {
    override fun toString(): String = "ClaimRequest"
}

/** Who can message the caller (ADR 0016). The server enforces it. */
enum class BipeWhoCanMessage(val id: String) {
    EVERYONE("everyone"),
    VERIFIED("verified"),
    CONTACTS("contacts"),
}

@Serializable
data class BipeMessagingSetting(
    @SerialName("who_can_message") private val whoCanMessageId: String,
) {
    constructor(whoCanMessage: BipeWhoCanMessage) : this(whoCanMessage.id)

    /** An unknown value from a newer server reads as the strictest one the app knows. */
    val whoCanMessage: BipeWhoCanMessage
        get() = BipeWhoCanMessage.entries.firstOrNull { it.id == whoCanMessageId } ?: BipeWhoCanMessage.CONTACTS
}

/** Who can find the caller (ADR 0063). */
@Serializable
data class BipeDiscoverySettings(
    /** People who have the caller's number can find them. Default `true`. */
    @SerialName("by_phone") val byPhone: Boolean,
)

/** The caller's linked phone number status. Never the number. */
@Serializable
data class BipePhoneStatus(
    @SerialName("linked") val linked: Boolean,
    @SerialName("discoverable_by_phone") val discoverableByPhone: Boolean,
)

@Serializable
internal data class HandleChangesRequest(
    @SerialName("user_ids") val userIds: List<String>,
    @SerialName("since") val since: String? = null,
)

@Serializable
internal data class HandleChangesResponse(@SerialName("changes") val changes: List<BipeHandleChange>)

@Serializable
internal data class ContactsLookupRequest(@SerialName("phones") val phones: List<String>) {
    override fun toString(): String = "ContactsLookupRequest(${phones.size})"
}

@Serializable
internal data class ContactsLookupResponse(@SerialName("matches") val matches: List<BipeContactMatch>)

@Serializable
internal data class ResolveHandlesRequest(@SerialName("user_ids") val userIds: List<String>)

@Serializable
internal data class ResolveHandlesResponse(@SerialName("handles") val handles: Map<String, String>)

@Serializable
internal data class SetHandleRequest(@SerialName("handle") val handle: String)

@Serializable
internal data class BipeErrorBody(
    @SerialName("error") val error: String,
    @SerialName("reason") val reason: String? = null,
    @SerialName("tries_left") val triesLeft: Int? = null,
)

/** ISO 8601 with `Z` or an offset, with or without fractional seconds. */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("BipeInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

/** `GET /v1/web-link/place` (ADR 0118). Every field is optional. */
@Serializable
data class BipeWebLinkPlace(
    @SerialName("city") val city: String? = null,
    @SerialName("region") val region: String? = null,
    /** ISO 3166-1 alpha-2. */
    @SerialName("country") val country: String? = null,
) {
    /** "City, Region" in Brazil; the country name is added for a place abroad. `null` when the place is unknown. Same rule as iOS. */
    fun label(locale: java.util.Locale = java.util.Locale.getDefault()): String? {
        val parts = listOfNotNull(city?.takeIf { it.isNotEmpty() }, region?.takeIf { it.isNotEmpty() }).toMutableList()
        val country = country?.takeIf { it.isNotEmpty() }
        if (country != null && (!country.equals("BR", ignoreCase = true) || parts.isEmpty())) {
            parts += java.util.Locale.Builder().setRegion(country).build().getDisplayCountry(locale).ifEmpty { country }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/** The answer of `GET /v1/badges/{userId}`. */
sealed interface BipeBadgeLookup {
    /** HTTP 200: the envelope JSON as the server sent it. Verify these exact bytes. */
    data class Envelope(val json: String) : BipeBadgeLookup

    /** HTTP 404: no badge. */
    data object None : BipeBadgeLookup

    /** HTTP 410: the account had a badge and its claim ended. */
    data class Gone(val reason: BipeBadgeGoneReason) : BipeBadgeLookup
}

/** The contract's `BadgeGone.reason` enum. */
enum class BipeBadgeGoneReason(val id: String) {
    REVOKED("revoked"),
    NOT_ACTIVE("not_active"),
    NOT_FOUND("not_found"),
    STAFF("staff");

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** An unknown or missing reason is [REVOKED]: the contract uses it for any other end of the verification. */
        fun fromBody(body: String?): BipeBadgeGoneReason {
            val reason = body?.let { runCatchingExceptions { json.decodeFromString(BadgeGoneBody.serializer(), it) }.getOrNull() }?.reason
            return entries.firstOrNull { it.id == reason } ?: REVOKED
        }
    }
}

/**
 * `GET /v1/banner`: the remote critical banner above the chat list (ADR 0117, bipe-chat/bipe#386). Port of
 * `remoteBannerState()` in bipe-shared; `test-vectors/remote-banner.json` holds the cases.
 */
@Serializable
data class BipeRemoteBanner(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String,
    @SerialName("text") val text: String,
    @SerialName("link") val link: String,
    @SerialName("min_app_version") val minAppVersion: String? = null,
    @SerialName("update_links") val updateLinks: UpdateLinks? = null,
    @SerialName("expires_at") val expiresAt: String,
) {
    @Serializable
    data class UpdateLinks(
        @SerialName("ios") val ios: String,
        @SerialName("android") val android: String,
    )

    sealed interface State {
        data object Hidden : State

        /** Opens [link]. The person can dismiss it by [id]. */
        data object Notice : State

        /** The app is below [minAppVersion]: opens the update link and cannot be dismissed. */
        data class Update(val url: String) : State
    }

    fun state(appVersion: String, now: Instant = Instant.now()): State {
        val expiry = runCatchingExceptions { OffsetDateTime.parse(expiresAt).toInstant() }.getOrNull() ?: return State.Hidden
        if (!now.isBefore(expiry)) return State.Hidden
        if (minAppVersion != null && updateLinks != null && compareVersions(appVersion, minAppVersion) < 0) {
            return State.Update(updateLinks.android)
        }
        return State.Notice
    }

    companion object {
        /** Compares dotted versions part by part as numbers. Each part counts its leading digits only ("2-dev" is 2). */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split(".")
            val pb = b.split(".")
            for (index in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrNull(index).leadingNumber()
                val y = pb.getOrNull(index).leadingNumber()
                if (x != y) return if (x < y) -1 else 1
            }
            return 0
        }

        private fun String?.leadingNumber(): Long = this?.takeWhile { it in '0'..'9' }?.toLongOrNull() ?: 0L
    }
}

@Serializable
internal data class BadgeGoneBody(val reason: String? = null)

@Serializable
data class BipeRemoteBannerResponse(
    @SerialName("banner") val banner: BipeRemoteBanner? = null,
)

/** `GovbrChallenge` (bipe#545). JSON keys are camelCase here, as in the contract. */
@Serializable
data class BipeGovbrChallenge(
    @SerialName("verificationId") val verificationId: String,
    /** The challenge PDF, base64. */
    @SerialName("pdf") val pdf: String,
    @SerialName("fileName") val fileName: String,
    @SerialName("expiresAt") val expiresAt: String,
)

/** `VerificationStatus`. `state` is `pending`, `verified`, `rejected` or `expired`; `reason` comes with `rejected`. */
@Serializable
data class BipeVerificationStatus(
    @SerialName("verificationId") val verificationId: String,
    @SerialName("state") val state: String,
    @SerialName("badge") val badge: BadgeEnvelope? = null,
    @SerialName("reason") val reason: String? = null,
)
