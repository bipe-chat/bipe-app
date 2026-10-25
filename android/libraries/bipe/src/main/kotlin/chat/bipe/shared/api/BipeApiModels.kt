// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.BipeHandleProblem
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Models of bipe-shared/api/openapi.yaml that sign-up, invites, handles, terms and device-first accounts use.
// JSON keys are snake_case. Types that hold a secret never print it.

/** `SignupRequest`. Device-first (ADR 0057): no password, the server makes one. */
@Serializable
data class BipeSignupRequest(
    @SerialName("invite_code") val inviteCode: String? = null,
    /** A recovery fallback only, never a login. */
    @SerialName("email") val email: String? = null,
    /** Must pass [chat.bipe.shared.account.BipePin.problem]. The server checks it again. */
    @SerialName("pin") val pin: String? = null,
    /** `pt` or `en`: the language of the e-mails Bipe sends. */
    @SerialName("lang") val lang: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    /** Same key on a retry: the same account (ADR 0094). */
    @SerialName("signup_key") val signupKey: String? = null,
    /** The legal version the person ticked (ADR 0113). */
    @SerialName("terms_version") val termsVersion: String? = null,
    /** Android Key Attestation (bipe-chat/bipe#92). `null` when the phone cannot attest: the server decides. */
    @SerialName("attestation") val attestation: BipeAttestation? = null,
) {
    override fun toString(): String = "BipeSignupRequest"
}

/** `Attestation`, Android form: the certificate chain of a new Keystore key, leaf first (ADR 0043). */
@Serializable
data class BipeAttestation(
    /** Standard base64 of each DER certificate. */
    @SerialName("key_attestation_chain") val keyAttestationChain: List<String>,
    /** From `POST /v1/attest/challenge`; the key carries its UTF-8 bytes. */
    @SerialName("challenge") val challenge: String,
) {
    override fun toString(): String = "BipeAttestation(${keyAttestationChain.size} certificates)"
}

@Serializable
internal data class BipeAttestChallengeResponse(@SerialName("challenge") val challenge: String)

/** `SignupConfig`. An app that cannot read it assumes [inviteRequired] is `true`. */
@Serializable
data class BipeSignupConfig(
    @SerialName("invite_required") val inviteRequired: Boolean,
    @SerialName("terms_version") val termsVersion: String? = null,
)

@Serializable
internal data class BipeSignupResumeRequest(@SerialName("signup_key") val signupKey: String) {
    override fun toString(): String = "BipeSignupResumeRequest"
}

/** `SignupResponse`. [password] and [recoveryToken] come once. */
@Serializable
data class BipeSignupResponse(
    @SerialName("user_id") val userId: String,
    @SerialName("handle") val handle: String? = null,
    @SerialName("inviter_user_id") val inviterUserId: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("recovery_token") val recoveryToken: String? = null,
) {
    val credentials: BipeCredentials?
        get() = if (password != null && recoveryToken != null) BipeCredentials(userId, password, recoveryToken) else null

    override fun toString(): String = "BipeSignupResponse($userId)"
}

/** `Credentials`. The password stays on this device; the token is the way back with the PIN. */
@Serializable
data class BipeCredentials(
    @SerialName("user_id") val userId: String,
    @SerialName("password") val password: String,
    @SerialName("recovery_token") val recoveryToken: String,
) {
    override fun toString(): String = "BipeCredentials($userId)"
}

/** `InvitePreview`. */
@Serializable
data class BipeInvitePreview(
    @SerialName("valid") val valid: Boolean,
    /** Without @. */
    @SerialName("inviter_handle") val inviterHandle: String? = null,
)

/** `HandleAvailability`. */
@Serializable
data class BipeHandleAvailability(
    @SerialName("handle") val handle: String,
    @SerialName("available") val available: Boolean,
    @SerialName("reason") val reason: String? = null,
) {
    /** A reason this app does not know counts as taken. */
    val problem: BipeHandleProblem?
        get() = reason?.let { BipeHandleProblem.fromId(it) ?: BipeHandleProblem.TAKEN }
}

/** Contract `HandleChange` (ADR 0066). A first set has no `old`, a removal has no `new`. */
@Serializable
data class BipeHandleChange(
    @SerialName("user_id") val userId: String,
    @SerialName("old") val old: String? = null,
    @SerialName("new") val new: String? = null,
    @Serializable(with = InstantSerializer::class) @SerialName("at") val at: Instant,
) {
    /** A stable ID for the local timeline row. */
    val lineId: String get() = "bipe-handle-change-$userId-${at.toEpochMilli()}"

    companion object {
        /** The most Matrix IDs per `POST /v1/handle-changes` call. */
        const val BATCH_SIZE = 100
    }
}

@Serializable
internal data class BipeHandleChangesRequest(
    @SerialName("user_ids") val userIds: List<String>,
    @SerialName("since") val since: String? = null,
)

@Serializable
internal data class BipeHandleChangesResponse(@SerialName("changes") val changes: List<BipeHandleChange>)

/** `MyHandle`. */
@Serializable
data class BipeMyHandle(
    @SerialName("user_id") val userId: String,
    @SerialName("handle") val handle: String? = null,
    /** `null` when a change is allowed now. */
    @Serializable(with = InstantSerializer::class) @SerialName("changeable_at") val changeableAt: Instant? = null,
)

/** `AccountStatus`, the parts the app reads now. */
@Serializable
data class BipeAccountStatus(
    @SerialName("user_id") val userId: String,
    /** The last legal version the account accepted (ADR 0113). */
    @SerialName("terms_version") val termsVersion: String? = null,
    @SerialName("max_accounts") val maxAccounts: Int? = null,
    /** Recoveries that run or ended in the last 7 days (recovery-alert). */
    @SerialName("recoveries") val recoveries: List<BipeRecovery> = emptyList(),
)

@Serializable
internal data class BipeWaitlistRequest(
    @SerialName("email") val email: String,
    @SerialName("platform") val platform: String,
    @SerialName("lang") val lang: String,
    @SerialName("source") val source: String,
) {
    override fun toString(): String = "BipeWaitlistRequest"
}

@Serializable
internal data class BipeTermsRequest(@SerialName("terms_version") val termsVersion: String)

@Serializable
internal data class BipeSetHandleRequest(@SerialName("handle") val handle: String)

@Serializable
internal data class BipeErrorBody(
    @SerialName("error") val error: String,
    @SerialName("reason") val reason: String? = null,
    @SerialName("tries_left") val triesLeft: Int? = null,
)

/** ISO 8601 with `Z` or an offset, with or without fractional seconds. */
internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("chat.bipe.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()
}

// Moved from libraries/bipe (BipeApiModels.kt) for BipeTrustProfile.

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

// Recovery (ADR 0057, ADR 0059). Contract: bipe-shared/api/openapi.yaml, schemas RecoveryStarted and RecoveryClaim.

@Serializable
internal data class BipeTokenRecoveryRequest(
    @SerialName("recovery_token") val recoveryToken: String,
    @SerialName("pin") val pin: String,
) {
    override fun toString(): String = "BipeTokenRecoveryRequest"
}

@Serializable
internal data class BipeRecoveryEmailRequest(@SerialName("email") val email: String) {
    override fun toString(): String = "BipeRecoveryEmailRequest"
}

/** Exactly one of [pin] (access) and [newPin] (PIN reset). */
@Serializable
internal data class BipeEmailConfirmRequest(
    @SerialName("token") val token: String,
    @SerialName("pin") val pin: String? = null,
    @SerialName("new_pin") val newPin: String? = null,
) {
    override fun toString(): String = "BipeEmailConfirmRequest"
}

@Serializable
internal data class BipeClaimRequest(@SerialName("claim_secret") val claimSecret: String) {
    override fun toString(): String = "BipeClaimRequest"
}

@Serializable
enum class BipeRecoveryPurpose {
    @SerialName("access") ACCESS,
    @SerialName("pin_reset") PIN_RESET,
}

@Serializable
enum class BipeRecoveryState {
    @SerialName("waiting") WAITING,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("expired") EXPIRED,
}

@Serializable
enum class BipeRecoveryMethod {
    @SerialName("token") TOKEN,
    @SerialName("email") EMAIL,
    @SerialName("guardian") GUARDIAN,
    @SerialName("cpf") CPF,
}

/** `Recovery`, as GET /v1/me/account lists it for the old devices. */
@Serializable
data class BipeRecovery(
    @SerialName("id") val id: String,
    @SerialName("method") val method: BipeRecoveryMethod,
    @SerialName("purpose") val purpose: BipeRecoveryPurpose,
    @SerialName("state") val state: BipeRecoveryState,
    @Serializable(with = InstantSerializer::class) @SerialName("created_at") val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant? = null,
)

/** `RecoveryStarted`. The new device keeps it, on this device only, until the claim ends. */
@Serializable
data class BipeRecoveryStarted(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("claim_secret") val claimSecret: String,
    @SerialName("purpose") val purpose: BipeRecoveryPurpose,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant,
) {
    override fun toString(): String = "BipeRecoveryStarted($recoveryId)"
}

/** `RecoveryClaim`. [credentials] come once, when an access recovery completes with this call. */
@Serializable
data class BipeRecoveryClaim(
    @SerialName("state") val state: BipeRecoveryState,
    @SerialName("purpose") val purpose: BipeRecoveryPurpose,
    @Serializable(with = InstantSerializer::class) @SerialName("ready_at") val readyAt: Instant? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("password") val password: String? = null,
    @SerialName("recovery_token") val recoveryToken: String? = null,
) {
    val credentials: BipeCredentials?
        get() = if (userId != null && password != null && recoveryToken != null) BipeCredentials(userId, password, recoveryToken) else null

    override fun toString(): String = "BipeRecoveryClaim($state)"
}
