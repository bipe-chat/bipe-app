// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import chat.bipe.shared.R
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** Type StateLine of spec timeline-rows (bipe-chat/bipe#661): a group membership or group profile change. */
data class StateLine(
    val change: Change,
    /** The sender's user ID. */
    val actorId: String,
    /** Membership changes only: the people of the folded line, oldest first, each once. */
    val targetIds: List<String>,
    /** RENAMED only. */
    val newName: String? = null,
) {
    enum class Change {
        JOINED, LEFT, INVITED, REMOVED, BANNED, RENAMED, NAME_REMOVED, PHOTO_CHANGED, PHOTO_REMOVED, DESCRIPTION_CHANGED, DESCRIPTION_REMOVED;

        val isMembership: Boolean get() = this in setOf(JOINED, LEFT, INVITED, REMOVED, BANNED)
    }
}

/** Spec timeline-rows stateLine: which group events become lines, how they fold and what they say. */
object StateLines {
    /** The line of an event, or null when the event is not a stateLine. The caller skips DMs (ADR 0141). */
    fun line(event: EventTimelineItem): StateLine? = when (val content = event.content) {
        is TimelineItemContent.RoomMembership -> {
            val change = when (content.change) {
                MembershipChange.JOINED, MembershipChange.INVITATION_ACCEPTED -> StateLine.Change.JOINED
                MembershipChange.LEFT -> StateLine.Change.LEFT
                MembershipChange.INVITED, MembershipChange.KNOCK_ACCEPTED -> StateLine.Change.INVITED
                MembershipChange.KICKED -> StateLine.Change.REMOVED
                MembershipChange.BANNED, MembershipChange.KICKED_AND_BANNED -> StateLine.Change.BANNED
                else -> null
            }
            change?.let { StateLine(it, event.sender, listOf(content.userId)) }
        }
        is TimelineItemContent.State -> when (val state = content.content) {
            is OtherState.RoomName -> state.name?.takeIf { it.isNotBlank() }
                ?.let { StateLine(StateLine.Change.RENAMED, event.sender, emptyList(), newName = it) }
                ?: StateLine(StateLine.Change.NAME_REMOVED, event.sender, emptyList())
            is OtherState.RoomAvatar -> StateLine(
                if (state.url.isNullOrBlank()) StateLine.Change.PHOTO_REMOVED else StateLine.Change.PHOTO_CHANGED,
                event.sender,
                emptyList(),
            )
            is OtherState.RoomTopic -> StateLine(
                if (state.topic.isNullOrBlank()) StateLine.Change.DESCRIPTION_REMOVED else StateLine.Change.DESCRIPTION_CHANGED,
                event.sender,
                emptyList(),
            )
            else -> null
        }
        else -> null
    }

    /**
     * The previous line with this one folded in, or null when they do not fold: the same membership change, and for
     * INVITED, REMOVED and BANNED the same sender.
     */
    fun folded(line: StateLine, previous: StateLine): StateLine? {
        if (!line.change.isMembership || line.change != previous.change) return null
        if (line.change != StateLine.Change.JOINED && line.change != StateLine.Change.LEFT && line.actorId != previous.actorId) return null
        return previous.copy(targetIds = (previous.targetIds + line.targetIds).distinct())
    }

    fun text(line: StateLine, name: (String) -> String, format: (Int, List<String>) -> String): String {
        val names = line.targetIds.map(name)
        val people = when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> format(R.string.bipe_app_state_names_two, names)
            else -> format(R.string.bipe_app_state_names_more, listOf(names[0], names[1], (names.size - 2).toString()))
        }
        val actor = name(line.actorId)
        val many = names.size > 1
        return when (line.change) {
            StateLine.Change.JOINED -> format(if (many) R.string.bipe_app_state_joined_many else R.string.bipe_app_state_joined_one, listOf(people))
            StateLine.Change.LEFT -> format(if (many) R.string.bipe_app_state_left_many else R.string.bipe_app_state_left_one, listOf(people))
            StateLine.Change.INVITED -> format(R.string.bipe_app_state_invited, listOf(actor, people))
            StateLine.Change.REMOVED -> format(R.string.bipe_app_state_removed, listOf(actor, people))
            StateLine.Change.BANNED -> format(R.string.bipe_app_state_banned, listOf(actor, people))
            StateLine.Change.RENAMED -> format(R.string.bipe_app_state_renamed, listOf(actor, line.newName.orEmpty()))
            StateLine.Change.NAME_REMOVED -> format(R.string.bipe_app_state_name_removed, listOf(actor))
            StateLine.Change.PHOTO_CHANGED -> format(R.string.bipe_app_state_photo_changed, listOf(actor))
            StateLine.Change.PHOTO_REMOVED -> format(R.string.bipe_app_state_photo_removed, listOf(actor))
            StateLine.Change.DESCRIPTION_CHANGED -> format(R.string.bipe_app_state_description_changed, listOf(actor))
            StateLine.Change.DESCRIPTION_REMOVED -> format(R.string.bipe_app_state_description_removed, listOf(actor))
        }
    }
}
