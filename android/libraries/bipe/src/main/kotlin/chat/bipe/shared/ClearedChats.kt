// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import org.json.JSONObject

/**
 * Cleared DMs, "Apagar conversa" (bipe-chat/bipe#464, ADR 0141). Rules: bipe-shared registries/cleared-chats.json and
 * the reference functions clearedChat* in tools/lib/registries.mjs; cases in test-vectors/cleared-chats.json.
 * The content of the global account data event is { "rooms": { "<room_id>": { "ts": <ms> } } }.
 */
object ClearedChats {
    const val EVENT_TYPE = "chat.bipe.cleared_chats"

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    /** The parsed content of the event, or null when there is none or it is not an object. */
    fun parse(json: String?): JSONObject? = json?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** clearedChatMarker: the marker (ms) of a room, or null. A malformed event or entry counts as not cleared. */
    fun marker(content: JSONObject?, roomId: String): Long? = ts(content?.optJSONObject("rooms")?.opt(roomId))

    /** Every valid marker of the content, by room ID. */
    fun markers(content: JSONObject?): Map<String, Long> {
        val rooms = content?.optJSONObject("rooms") ?: return emptyMap()
        return rooms.keys().asSequence().mapNotNull { id -> ts(rooms.opt(id))?.let { id to it } }.toMap()
    }

    /** clearedChatHidden: the chat list hides the room while it has a marker and no event after it. */
    fun hidden(marker: Long?, latestTs: Long?): Boolean = marker != null && (latestTs == null || latestTs <= marker)

    /** clearedChatKeepsEvent: the timeline and search keep an event with this origin_server_ts. */
    fun keepsEvent(marker: Long?, eventTs: Long): Boolean = marker == null || eventTs > marker

    /**
     * clearedChatsWithClear: the content to write when the user clears roomId at ts. Keeps every other key and entry
     * as it is. The marker never moves back.
     */
    fun withClear(content: JSONObject?, roomId: String, ts: Long): JSONObject {
        val base = JSONObject(content?.toString() ?: "{}")
        val rooms = base.optJSONObject("rooms") ?: JSONObject()
        val old = ts(rooms.opt(roomId))
        rooms.put(roomId, JSONObject().put("ts", maxOf(old ?: 0L, ts)))
        base.put("rooms", rooms)
        return base
    }

    /** A valid entry is an object whose ts is a whole number of 0 or more, up to 2^53 - 1. */
    private fun ts(entry: Any?): Long? {
        val obj = entry as? JSONObject ?: return null
        val value = obj.opt("ts")
        if (value !is Number) return null
        val double = value.toDouble()
        if (double < 0 || double > MAX_SAFE_INTEGER || double != Math.floor(double)) return null
        return value.toLong()
    }
}
