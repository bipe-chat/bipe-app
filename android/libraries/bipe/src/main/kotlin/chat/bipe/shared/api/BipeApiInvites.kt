// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Personal invites, from the same contract as BipeKit BipeAPIClient. Codes are returned only on creation. */
suspend fun BipeApiClient.myInvites(accessToken: String): BipeMyInvites =
    send("GET", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeMyInvites.serializer())

suspend fun BipeApiClient.createInvite(accessToken: String): BipeCreatedInvite =
    send("POST", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeCreatedInvite.serializer())

suspend fun BipeApiClient.nameInvite(id: String, name: String, accessToken: String) {
    send<Unit>("PUT", listOf("v1", "me", "invites", id), body = encode(InviteName.serializer(), InviteName(name)),
        accessToken = accessToken, response = null)
}

suspend fun BipeApiClient.revokeInvite(id: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "invites", id), accessToken = accessToken, response = null)
}

@Serializable
private data class InviteName(val name: String)

@Serializable
data class BipeMyInvites(
    val quota: Int,
    val available: Int,
    @SerialName("next_available_at") val nextAvailableAt: String? = null,
    val invites: List<BipeInvite>,
)

@Serializable
data class BipeInvite(
    val id: String,
    val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("joined_user_id") val joinedUserId: String? = null,
    val name: String? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isUsed: Boolean get() = status == "used"
}

@Serializable
data class BipeCreatedInvite(
    val id: String,
    val code: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "BipeCreatedInvite($id)"
}
