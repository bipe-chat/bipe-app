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
) {
    override fun toString(): String = "BipeSignupRequest"
}

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
