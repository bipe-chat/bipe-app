// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.clearedchats

import chat.bipe.shared.runCatchingExceptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The markers of cleared DMs (bipe-chat/bipe#464), from the global account data event `chat.bipe.cleared_chats`.
 *
 * Content: `{ "rooms": { "<room_id>": { "ts": <origin_server_ts in ms> } } }`. The marker is the time of the latest
 * event of the room when the user cleared it. A malformed event or entry counts as not cleared. The rules match
 * `clearedChat*` in bipe-shared/tools/lib/registries.mjs and the shared test vectors (test-vectors/cleared-chats.json).
 */
data class ClearedChats(
    private val markers: Map<String, Long>,
) {
    /** The marker (ms) of [roomId], or null when the room is not cleared. */
    fun marker(roomId: String): Long? = markers[roomId]

    /** True when the room list hides [roomId]: it has a marker and no event after it. */
    fun isHidden(roomId: String, latestTs: Long?): Boolean = isHidden(marker(roomId), latestTs)

    /** These markers plus [others]. For a room in both, the larger marker wins, because a marker never moves back. */
    fun mergedWith(others: Map<String, Long>): ClearedChats {
        if (others.isEmpty()) return this
        val merged = markers.toMutableMap()
        for ((roomId, ts) in others) {
            merged[roomId] = maxOf(merged[roomId] ?: ts, ts)
        }
        return ClearedChats(merged)
    }

    companion object {
        val EMPTY = ClearedChats(emptyMap())

        // Number.MAX_SAFE_INTEGER in the reference implementation.
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

        /** Reads the raw JSON content of the event. Null or malformed content gives [EMPTY]. */
        fun parse(content: String?): ClearedChats {
            val rooms = rooms(parseElement(content)) ?: return EMPTY
            val markers = rooms.mapNotNull { (roomId, entry) -> ts(entry)?.let { roomId to it } }.toMap()
            return ClearedChats(markers)
        }

        /** True when the room list hides the room. [latestTs] is null when the room has no latest event. */
        fun isHidden(marker: Long?, latestTs: Long?): Boolean = marker != null && (latestTs == null || latestTs <= marker)

        /** True when the timeline and search keep an event with this origin_server_ts. */
        fun keepsEvent(marker: Long?, eventTs: Long): Boolean = marker == null || eventTs > marker

        /**
         * The content to write when the user clears [roomId] at [ts]. Keeps every other key and entry as it is.
         * The marker never moves back.
         */
        fun contentClearing(content: String?, roomId: String, ts: Long): String {
            val base = parseElement(content) as? JsonObject ?: JsonObject(emptyMap())
            val rooms = rooms(base) ?: JsonObject(emptyMap())
            val old = ts(rooms[roomId])
            val newTs = if (old != null && old > ts) old else ts
            val newRooms = JsonObject(rooms + (roomId to JsonObject(mapOf("ts" to JsonPrimitive(newTs)))))
            return JsonObject(base + ("rooms" to newRooms)).toString()
        }

        private fun parseElement(content: String?): JsonElement? {
            if (content == null) return null
            return runCatchingExceptions { Json.parseToJsonElement(content) }.getOrNull()
        }

        private fun rooms(content: JsonElement?): JsonObject? = (content as? JsonObject)?.get("rooms") as? JsonObject

        // A safe integer >= 0, as Number.isSafeInteger in the reference implementation. A string is not a number.
        private fun ts(entry: JsonElement?): Long? {
            val primitive = (entry as? JsonObject)?.get("ts") as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val text = primitive.content
            val value = text.toLongOrNull()
                ?: text.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
                ?: return null
            return value.takeIf { it in 0..MAX_SAFE_INTEGER }
        }
    }
}
