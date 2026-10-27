// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.settings

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.runCatchingExceptions
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

/**
 * Novidades do Bipe is a named room, not a DM (bipe-chat/bipe#809, bipe-shared spec chat-list notes). bipe-api made
 * news rooms with `is_direct` before, so the user's `m.direct` lists them. The app takes the news room out once per
 * account. The same cases are in `NewsDirectCleanupTests.swift`.
 */
object BipeNewsDirectCleanup {
    /** The global account data event that lists the DM rooms of the account by the other user (Matrix spec, m.direct). */
    const val DIRECT_EVENT_TYPE = "m.direct"

    /**
     * The `m.direct` content without [roomId], or null when [roomId] is not in it (nothing to write). Every other
     * entry stays as it is. A user whose list becomes empty goes.
     */
    fun removing(roomId: String, content: String?): String? {
        if (content == null) return null
        val parsed = runCatchingExceptions { Json.parseToJsonElement(content) }.getOrNull() as? JsonObject ?: return null
        var changed = false
        val out = LinkedHashMap<String, JsonElement>()
        for ((user, value) in parsed) {
            val rooms = value as? JsonArray
            if (rooms == null) {
                out[user] = value
                continue
            }
            val kept = rooms.filterNot { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == roomId }
            if (kept.size != rooms.size) {
                changed = true
                if (kept.isEmpty()) continue
            }
            out[user] = JsonArray(kept)
        }
        return if (changed) JsonObject(out).toString() else null
    }
}
