// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Chats accepted from chat-requests (spec timeline-media fromStranger; bipe-shared registries/request-rooms.json).
 * Global account data [BipeRegistries.REQUEST_ROOMS_EVENT_TYPE] with content `{"rooms": [roomId, ...]}`, newest last.
 * Same rules as ios/BipeKit BipeRequestRooms.swift.
 */
object BipeRequestRooms {
    const val EVENT_TYPE = BipeRegistries.REQUEST_ROOMS_EVENT_TYPE

    /** The room IDs in the account data content, without duplicates. Empty on null or malformed content. */
    fun parse(json: String?): List<String> {
        if (json == null) return emptyList()
        val rooms = runCatching { Json.parseToJsonElement(json) }.getOrNull()
            .let { it as? JsonObject }?.get("rooms") as? JsonArray ?: return emptyList()
        return rooms.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.distinct()
    }

    /** The list with the new room IDs last. A room ID already in the list keeps its place. Keeps the newest [BipeRegistries.REQUEST_ROOMS_MAX]. */
    fun merged(rooms: List<String>, adding: List<String>): List<String> =
        (rooms + adding).distinct().takeLast(BipeRegistries.REQUEST_ROOMS_MAX)

    /** The account data content for a list. */
    fun content(rooms: List<String>): String = buildJsonObject {
        put("rooms", JsonArray(rooms.map { JsonPrimitive(it) }))
    }.toString()
}
