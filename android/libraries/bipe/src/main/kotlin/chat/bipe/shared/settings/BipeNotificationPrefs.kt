// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

import chat.bipe.shared.api.BipeApiClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Contract: `NotificationPrefs`. Which join pushes the account gets (bipe-chat/bipe#967, spec notification-settings).
 * The API reads them before it sends invite_joined or group_link_joined, so they apply on every device of the account.
 */
@Serializable
data class BipeNotificationPrefs(
    @SerialName("invite_joined") val inviteJoined: Boolean,
    @SerialName("group_link_joined") val groupLinkJoined: Boolean,
)

/** `GET /v1/me/notification-prefs`. No saved prefs: both true. */
suspend fun BipeApiClient.notificationPrefs(accessToken: String): BipeNotificationPrefs =
    send("GET", listOf("v1", "me", "notification-prefs"), accessToken = accessToken, response = BipeNotificationPrefs.serializer())

/** `PUT /v1/me/notification-prefs`. */
suspend fun BipeApiClient.setNotificationPrefs(prefs: BipeNotificationPrefs, accessToken: String): BipeNotificationPrefs = send(
    "PUT",
    listOf("v1", "me", "notification-prefs"),
    body = encode(BipeNotificationPrefs.serializer(), prefs),
    accessToken = accessToken,
    response = BipeNotificationPrefs.serializer(),
)
