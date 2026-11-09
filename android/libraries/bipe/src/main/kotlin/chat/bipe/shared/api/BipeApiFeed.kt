// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.api

import chat.bipe.shared.generated.BipeGroupPowerLevels
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Public feed (bipe ADR 0280, bipe-chat/bipe#1260), from the same contract as BipeKit BipeFeed.swift. */

/** `GET /v1/me/feed`. NOT_FOUND when staff never linked a feed to the caller's handle. */
suspend fun BipeApiClient.myFeed(accessToken: String): BipeMyFeed =
    send("GET", listOf("v1", "me", "feed"), accessToken = accessToken, response = BipeMyFeed.serializer())

/** `PUT /v1/me/feed`. Off takes the page down and stops new posts. */
suspend fun BipeApiClient.setMyFeed(enabled: Boolean, accessToken: String): BipeMyFeed =
    send("PUT", listOf("v1", "me", "feed"), body = encode(FeedEnabled.serializer(), FeedEnabled(enabled)),
        accessToken = accessToken, response = BipeMyFeed.serializer())

/** `GET /v1/me/feed/rooms/{roomId}`. NOT_FOUND unless the room posts to a feed and the caller is an admin there. */
suspend fun BipeApiClient.feedRoom(roomId: String, accessToken: String): BipeFeedRoom =
    send("GET", listOf("v1", "me", "feed", "rooms", roomId), accessToken = accessToken, response = BipeFeedRoom.serializer())

/** `POST /v1/me/feed/posts`: the plain copy of a group post. */
suspend fun BipeApiClient.publishFeedPost(text: String, accessToken: String): BipeFeedPost =
    send("POST", listOf("v1", "me", "feed", "posts"), body = encode(FeedText.serializer(), FeedText(text)),
        accessToken = accessToken, response = BipeFeedPost.serializer())

/** `DELETE /v1/me/feed/posts/{id}`. */
suspend fun BipeApiClient.deleteFeedPost(id: String, accessToken: String) {
    send<Unit>("DELETE", listOf("v1", "me", "feed", "posts", id), accessToken = accessToken, response = null)
}

@Serializable
private data class FeedEnabled(val enabled: Boolean)

@Serializable
private data class FeedText(val text: String)

@Serializable
data class BipeMyFeed(
    val handle: String,
    @SerialName("room_id") val roomId: String,
    val enabled: Boolean,
    val url: String,
)

@Serializable
data class BipeFeedRoom(val handle: String, val url: String)

@Serializable
data class BipeFeedPost(
    val id: String,
    val text: String,
    @SerialName("created_at") val createdAt: String,
    val url: String,
)

/**
 * The mark of a group post that also went to the public feed: the top-level object FEED_POST_KEY of
 * registries/group-power-levels.json with the feed post's id and url. A forward drops it (BipeForwardMark).
 */
object BipeFeedPostMark {
    private val json = Json { ignoreUnknownKeys = true }
    private val idShape = Regex("^[a-z0-9]{1,40}$")

    /** The extra content JSON for Timeline.sendWithExtraContent: only the mark. */
    fun extraContentJson(post: BipeFeedPost): String =
        buildJsonObject {
            put(BipeGroupPowerLevels.FEED_POST_KEY, buildJsonObject {
                put("id", post.id)
                put("url", post.url)
            })
        }.toString()

    /** The feed post id from event JSON (the whole event or its content). null without a well-formed mark. */
    fun postId(eventJson: String?): String? {
        if (eventJson == null) return null
        val root = runCatching { json.parseToJsonElement(eventJson) as? JsonObject }.getOrNull() ?: return null
        val content = root["content"] as? JsonObject ?: root
        val mark = content[BipeGroupPowerLevels.FEED_POST_KEY] as? JsonObject ?: return null
        val id = (mark["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return id.takeIf { idShape.matches(it) }
    }
}
