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

/** Group links (ADR 0184). `GET /v1/group-links/{code}`, no auth: the group name and member count, never member names. */
suspend fun BipeApiClient.groupLinkPreview(code: String): BipeGroupLinkPreview =
    send("GET", listOf("v1", "group-links", code), response = BipeGroupLinkPreview.serializer())

/** `POST /v1/group-links/{code}/join`: the groups service account invites the caller; then join the room. */
suspend fun BipeApiClient.joinGroupLink(code: String, accessToken: String): String =
    send("POST", listOf("v1", "group-links", code, "join"), accessToken = accessToken, response = GroupLinkJoined.serializer()).roomId

suspend fun BipeApiClient.groupLinks(roomId: String, accessToken: String): List<BipeGroupLink> =
    send("GET", listOf("v1", "me", "group-links"), accessToken = accessToken, response = GroupLinkList.serializer(),
        query = listOf("room_id" to roomId)).links

/** The code is returned once. Invite the groups service account first. */
suspend fun BipeApiClient.createGroupLink(roomId: String, accessToken: String): BipeCreatedGroupLink =
    send("POST", listOf("v1", "me", "group-links"), body = encode(GroupLinkRequest.serializer(), GroupLinkRequest(roomId)),
        accessToken = accessToken, response = BipeCreatedGroupLink.serializer())

suspend fun BipeApiClient.revokeGroupLink(id: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "group-links", id), accessToken = accessToken, response = null)
}

@Serializable
private data class GroupLinkRequest(@SerialName("room_id") val roomId: String)

@Serializable
private data class GroupLinkList(val links: List<BipeGroupLink>)

@Serializable
private data class GroupLinkJoined(@SerialName("room_id") val roomId: String)

@Serializable
data class BipeGroupLinkPreview(
    val valid: Boolean,
    val name: String? = null,
    val members: Int? = null,
    @SerialName("inviter_name") val inviterName: String? = null,
    @SerialName("signs_up") val signsUp: Boolean? = null,
)

@Serializable
data class BipeGroupLink(
    val id: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("signs_up") val signsUp: Boolean,
    val joined: Int,
    val signups: Int,
    @SerialName("signups_left") val signupsLeft: Int,
)

@Serializable
data class BipeCreatedGroupLink(
    val id: String,
    val code: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("signs_up") val signsUp: Boolean,
) {
    override fun toString(): String = "BipeCreatedGroupLink($id)"
}
