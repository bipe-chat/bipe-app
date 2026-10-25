// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

/**
 * The date of a chat-list row (spec chat-list, bipe-chat/bipe#592). The SDK latest event skips encrypted events that
 * this phone cannot decrypt, so a room whose recent events are all undecryptable has no latest event and the row had
 * no date. The time of an event is never encrypted: such a row takes the time of the newest event in the room's local
 * timeline instead. Never the current time, never a guess.
 */
object BipeRowActivity {
    /** A message-like event (text, media, sticker, poll, deleted or undecryptable) or any other timeline event. */
    enum class Kind { MESSAGE, OTHER }

    /** One event of the room's local timeline: its origin_server_ts in milliseconds. */
    data class LocalEvent(val timestampMs: Long, val kind: Kind)

    /** Only a row without an SDK latest event reads the local timeline. */
    fun needsLocalTimeline(sdkLatestMs: Long?): Boolean = sdkLatestMs == null

    /**
     * The row time in milliseconds, or null while nothing is known on this phone.
     * 1. The SDK latest event. 2. The newest message-like local event, undecryptable ones included. 3. The newest local
     * event of any kind. A time known earlier in this account session is kept when it is newer than 2 and 3.
     */
    fun resolve(sdkLatestMs: Long?, localEvents: List<LocalEvent>, knownMs: Long?): Long? {
        if (sdkLatestMs != null) return sdkLatestMs
        val local = localEvents.filter { it.kind == Kind.MESSAGE }.maxOfOrNull { it.timestampMs }
            ?: localEvents.maxOfOrNull { it.timestampMs }
        return listOfNotNull(local, knownMs).maxOrNull()
    }
}
