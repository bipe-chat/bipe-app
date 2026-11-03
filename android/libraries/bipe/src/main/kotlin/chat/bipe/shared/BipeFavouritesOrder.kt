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
 * Favourites order (spec chat-list; bipe-shared registries/chat-favourites.json). Global account data
 * [BipeRegistries.CHAT_FAVOURITES_ORDER_EVENT_TYPE] with content `{"rooms": [roomId, ...]}`, first room first.
 * Same rules as ios/BipeKit BipeFavouritesOrder.swift.
 */
object BipeFavouritesOrder {
    const val EVENT_TYPE = BipeRegistries.CHAT_FAVOURITES_ORDER_EVENT_TYPE

    /** The room IDs in the account data content, in order, without duplicates. Empty on null or malformed content. */
    fun parse(json: String?): List<String> {
        if (json == null) return emptyList()
        val rooms = runCatching { Json.parseToJsonElement(json) }.getOrNull()
            .let { it as? JsonObject }?.get("rooms") as? JsonArray ?: return emptyList()
        return rooms.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }.distinct()
    }

    /** The account data content for an order. Keeps the first [BipeRegistries.CHAT_FAVOURITES_ORDER_MAX] room IDs. */
    fun content(rooms: List<String>): String = buildJsonObject {
        put("rooms", JsonArray(rooms.take(BipeRegistries.CHAT_FAVOURITES_ORDER_MAX).map { JsonPrimitive(it) }))
    }.toString()

    /**
     * Favourites in display order: the room IDs in [order] first, in order; then the others by name
     * (case-insensitive), then room ID.
     */
    fun <T> sorted(items: List<T>, order: List<String>, roomId: (T) -> String, name: (T) -> String): List<T> {
        val position = HashMap<String, Int>()
        order.forEachIndexed { index, id -> position.putIfAbsent(id, index) }
        return items.sortedWith(
            compareBy<T> { position[roomId(it)] ?: Int.MAX_VALUE }
                .thenBy(String.CASE_INSENSITIVE_ORDER, name)
                .thenBy(roomId),
        )
    }

    /** A new favourite goes last. */
    fun adding(roomId: String, order: List<String>): List<String> = order.filter { it != roomId } + roomId

    /** A removed favourite leaves the order. */
    fun removing(roomId: String, order: List<String>): List<String> = order.filter { it != roomId }

    /**
     * The order after the user moves [roomId] to [index] among [shown], the favourites of one scope in display order.
     * Shown rooms not yet in the order join it at the end first. The rooms of the other scope keep their positions.
     */
    fun moving(roomId: String, index: Int, shown: List<String>, order: List<String>): List<String> {
        val from = shown.indexOf(roomId)
        if (from < 0) return order
        val moved = shown.toMutableList().apply { removeAt(from); add(index.coerceIn(0, size), roomId) }
        val full = order + shown.filter { it !in order }
        val inScope = shown.toSet()
        val next = moved.iterator()
        return full.map { if (it in inScope && next.hasNext()) next.next() else it }
    }
}
