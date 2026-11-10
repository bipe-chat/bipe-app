// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.clearedchats

import chat.bipe.core.clearedChatHidden
import chat.bipe.core.clearedChatKeepsEvent
import chat.bipe.core.clearedChatMarkers
import chat.bipe.core.clearedChatsWithClear

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

        /** The valid markers of the account data content (bipe-core `clearedChatMarkers`). */
        fun parse(content: String?): ClearedChats = ClearedChats(clearedChatMarkers(content))

        fun isHidden(marker: Long?, latestTs: Long?): Boolean = clearedChatHidden(marker, latestTs)

        fun keepsEvent(marker: Long?, eventTs: Long): Boolean = clearedChatKeepsEvent(marker, eventTs)

        /** bipe-core `clearedChatsWithClear`: keeps every other key and entry; the marker never moves back. */
        fun contentClearing(content: String?, roomId: String, ts: Long): String = clearedChatsWithClear(content, roomId, ts)
    }
}
