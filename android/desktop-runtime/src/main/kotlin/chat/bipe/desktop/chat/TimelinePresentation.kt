// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

/** Retains SDK daily dividers in the same vector/index space as message and hidden event items. */
internal sealed interface TimelineSnapshot {
    data class Message(val row: MessageRow) : TimelineSnapshot
    data class Day(val timestamp: Long) : TimelineSnapshot
    data class ReadPoint(val readers: Set<String>) : TimelineSnapshot
}

internal fun List<TimelineSnapshot>.messageRows(): List<MessageRow> {
    var day: Long? = null
    return buildList {
        for (item in this@messageRows) when (item) {
            is TimelineSnapshot.Day -> day = item.timestamp
            is TimelineSnapshot.ReadPoint -> Unit
            is TimelineSnapshot.Message -> {
                add(item.row.copy(dayBefore = day))
                day = null
            }
        }
    }
}

/** Following is changed by user scrolling, never by a new event moving the list's stable anchor. */
class TimelineScrollPolicy {
    private enum class Position { FOLLOWING, READING }
    private var position = Position.FOLLOWING
    private var newestId: String? = null
    private var knownIds: Set<String> = emptySet()

    fun userScrolled(atNewest: Boolean) { position = if (atNewest) Position.FOLLOWING else Position.READING }
    fun jumpToNewest() { position = Position.FOLLOWING }

    fun messagesChanged(messages: List<MessageRow>): Boolean {
        val newest = messages.lastOrNull() ?: return false
        val arrivingOwn = newestId != null && newest.own && newest.id !in knownIds
        knownIds = messages.mapTo(mutableSetOf()) { it.id }
        if (newest.id == newestId) return false
        newestId = newest.id
        // Match the phone: an arriving own message returns to the composer; history/edits never do.
        if (arrivingOwn) position = Position.FOLLOWING
        return position == Position.FOLLOWING
    }
}

/** Never hide a group sender (including equal display names), a former DM member, or locked messages. */
fun MessageRow.showsSender(isDm: Boolean, peerId: String?): Boolean = !own &&
    (kind == MessageRow.Kind.UNDECRYPTABLE || !isDm || peerId == null || senderId != peerId)
