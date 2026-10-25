// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

import chat.bipe.shared.api.BipeApiClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Novidades do Bipe (ADR 0117, bipe-chat/bipe#387): the answer of GET, PUT and DELETE /v1/me/news. */
@Serializable
data class BipeNewsState(
    @SerialName("subscribed") val subscribed: Boolean,
    /** The user's news room, only when subscribed. */
    @SerialName("room_id") val roomId: String? = null,
)

/** `GET /v1/me/news`. */
suspend fun BipeApiClient.newsState(accessToken: String): BipeNewsState =
    send("GET", listOf("v1", "me", "news"), accessToken = accessToken, response = BipeNewsState.serializer())

/** `PUT /v1/me/news` (on: the kept or a new room) or `DELETE /v1/me/news` (off). */
suspend fun BipeApiClient.setNews(isOn: Boolean, accessToken: String): BipeNewsState =
    send(if (isOn) "PUT" else "DELETE", listOf("v1", "me", "news"), body = if (isOn) "{}" else null, accessToken = accessToken, response = BipeNewsState.serializer())
