// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Home-screen API calls from bipe-shared/api/openapi.yaml. Shape is not a verification claim (ADR 0132). */
suspend fun BipeApiClient.avatarAccountIsCompany(userId: String): Boolean =
    send("GET", listOf("v1", "accounts", userId, "type"), response = HomeAccountType.serializer()).accountType == "company"

suspend fun BipeApiClient.homeInviteQuota(accessToken: String): BipeHomeInviteQuota =
    send("GET", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeHomeInviteQuota.serializer())

suspend fun BipeApiClient.homeCreateInvite(accessToken: String): BipeHomeInvite =
    send("POST", listOf("v1", "me", "invites"), accessToken = accessToken, response = BipeHomeInvite.serializer())

@Serializable
private data class HomeAccountType(@SerialName("account_type") val accountType: String)

@Serializable
data class BipeHomeInviteQuota(
    val available: Int,
    @SerialName("next_available_at") val nextAvailableAt: String? = null,
)

@Serializable
data class BipeHomeInvite(
    val id: String,
    val code: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String,
) {
    override fun toString(): String = "BipeHomeInvite"
}
