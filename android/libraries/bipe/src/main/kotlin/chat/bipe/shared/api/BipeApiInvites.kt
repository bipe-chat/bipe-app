// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// Personal invites to Bipe (bipe-shared api/openapi.yaml: /v1/me/invites, /v1/me/invites/{inviteId}).
// The same calls as BipeKit's BipeAPIClient myInvites, createInvite, nameInvite and revokeInvite.

/** `InviteStatus`. An unknown status from a newer server reads as expired, so the list still loads. */
@Serializable(with = BipeInviteStatusSerializer::class)
enum class BipeInviteStatus(val id: String) {
    PENDING("pending"),
    USED("used"),
    EXPIRED("expired"),
    REVOKED("revoked"),
}

internal object BipeInviteStatusSerializer : KSerializer<BipeInviteStatus> {
    override val descriptor = PrimitiveSerialDescriptor("BipeInviteStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BipeInviteStatus {
        val id = decoder.decodeString()
        return BipeInviteStatus.entries.firstOrNull { it.id == id } ?: BipeInviteStatus.EXPIRED
    }

    override fun serialize(encoder: Encoder, value: BipeInviteStatus) = encoder.encodeString(value.id)
}

/** `Invite`. */
@Serializable
data class BipeInvite(
    @SerialName("id") val id: String,
    @SerialName("status") val status: BipeInviteStatus,
    @Serializable(with = InstantSerializer::class) @SerialName("created_at") val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
    /** Only when status is used. */
    @SerialName("joined_user_id") val joinedUserId: String? = null,
    /** The name the user gave the invite. Only they see it. */
    @SerialName("name") val name: String? = null,
)

/** `MyInvites`. */
@Serializable
data class BipeMyInvites(
    @SerialName("quota") val quota: Int,
    @SerialName("available") val available: Int,
    /** Only when available is 0: when the next slot comes back. */
    @Serializable(with = InstantSerializer::class) @SerialName("next_available_at") val nextAvailableAt: Instant? = null,
    @SerialName("invites") val invites: List<BipeInvite> = emptyList(),
)

/** `CreatedInvite`. The code is a secret: it shows only inside the link. */
@Serializable
data class BipeCreatedInvite(
    @SerialName("id") val id: String,
    @SerialName("code") val code: String,
    @SerialName("url") val url: String,
    @Serializable(with = InstantSerializer::class) @SerialName("expires_at") val expiresAt: Instant,
) {
    override fun toString(): String = "BipeCreatedInvite(id=$id)"
}

@Serializable
private data class InviteNameRequest(@SerialName("name") val name: String)

/** `GET /v1/me/invites`: quota and invites, newest first. */
suspend fun BipeApiClient.myInvites(accessToken: String): BipeMyInvites =
    send("GET", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeMyInvites.serializer())

/** `POST /v1/me/invites`: one single-use invite. Fails with invite_quota_exhausted when none is left. */
suspend fun BipeApiClient.createInvite(accessToken: String): BipeCreatedInvite =
    send("POST", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeCreatedInvite.serializer())

/** `PUT /v1/me/invites/{inviteId}`: an empty name removes it. */
suspend fun BipeApiClient.nameInvite(id: String, name: String, accessToken: String) {
    send<Unit>(
        "PUT",
        listOf("v1", "me", "invites", id),
        body = encode(InviteNameRequest.serializer(), InviteNameRequest(name)),
        accessToken = accessToken,
        response = null,
    )
}

/** `DELETE /v1/me/invites/{inviteId}`: a pending invite only. */
suspend fun BipeApiClient.revokeInvite(id: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "invites", id), accessToken = accessToken, response = null)
}
