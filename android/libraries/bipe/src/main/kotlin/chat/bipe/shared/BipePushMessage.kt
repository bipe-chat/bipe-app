// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

/**
 * A content-free push as it reaches the phone (spec notification-service): Sygnal's data message with format
 * event_id_only plus the pusher's default_payload. The account comes only from the random route, resolved on this phone.
 */
sealed interface BipePushMessage {
    /** A new event. [accountUserId] `null`: the route is unknown, so only the generic fallback may show. */
    data class Event(val accountUserId: String?, val roomId: String, val eventId: String) : BipePushMessage

    /** No event: the unread count of [accountUserId] changed, for example after a read on another device. */
    data class CountChanged(val accountUserId: String) : BipePushMessage

    /** A new device of [accountUserId] waits for approval (bipe-chat/bipe#628). Nothing to fetch: the sync brings the request. */
    data class DeviceApproval(val accountUserId: String) : BipePushMessage

    /**
     * A recovery of [accountUserId] started, or completed on a new device (bipe-chat/bipe#171). Nothing to fetch: the app
     * reads GET /v1/me/account and shows recovery-alert.
     */
    data class RecoveryAlert(val accountUserId: String, val completed: Boolean) : BipePushMessage

    /**
     * Someone joined with an invite of [accountUserId] (bipe-chat/bipe#967). Nothing to fetch: the app refreshes the
     * invites, which shows the join notice and sends the inviter's note.
     */
    data class InviteJoined(val accountUserId: String) : BipePushMessage

    /** Someone joined [roomId], a group of [accountUserId], with a link that account made (bipe-chat/bipe#967). */
    data class GroupLinkJoined(val accountUserId: String, val roomId: String?) : BipePushMessage

    /** Nothing this phone can act on. */
    /** A business push (bipe-chat/bipe#320): [kind] is one of BUSINESS_KINDS. */
    data class BusinessNotice(val accountUserId: String, val kind: String) : BipePushMessage

    data object Ignored : BipePushMessage

    companion object {
        const val ROOM_ID = "room_id"
        const val EVENT_ID = "event_id"
        const val ACCOUNT_ROUTE = "account_route"

        /** bipe-api adds this to the default_payload of a push that is not a room event (spec notification-service). */
        const val PUSH_KIND = "bipe_push_kind"
        const val DEVICE_APPROVAL = "device_approval"
        const val RECOVERY_STARTED = "recovery_started"
        const val RECOVERY_COMPLETED = "recovery_completed"
        const val INVITE_JOINED = "invite_joined"
        const val GROUP_LINK_JOINED = "group_link_joined"
        /** The business pushes (bipe-chat/bipe#320): content-free, so the phone shows fixed text. */
        val BUSINESS_KINDS = setOf(
            "booking_tomorrow", "booking_soon", "booking_changed", "order_updated", "queue_called",
            "work_new_booking", "work_new_order", "work_cancelled", "work_today",
        )
        /** group_link_joined only: the group. */
        const val PUSH_ROOM_ID = "bipe_room_id"

        fun parse(data: Map<String, String>, accountFor: (String) -> String?): BipePushMessage {
            val userId = data[ACCOUNT_ROUTE]?.takeIf { it.isNotBlank() }?.let(accountFor)
            // An approval nudge carries the fixed event ID $device_approval and no room: it never shows as a message.
            // So do the recovery alerts ($recovery_started, $recovery_completed) and the join pushes (bipe-chat/bipe#967).
            when (data[PUSH_KIND]) {
                DEVICE_APPROVAL -> return if (userId != null) DeviceApproval(userId) else Ignored
                RECOVERY_STARTED -> return if (userId != null) RecoveryAlert(userId, completed = false) else Ignored
                RECOVERY_COMPLETED -> return if (userId != null) RecoveryAlert(userId, completed = true) else Ignored
                INVITE_JOINED -> return if (userId != null) InviteJoined(userId) else Ignored
                GROUP_LINK_JOINED ->
                    return if (userId != null) GroupLinkJoined(userId, data[PUSH_ROOM_ID]?.takeIf { it.startsWith("!") }) else Ignored
            }
            data[PUSH_KIND]?.takeIf { it in BUSINESS_KINDS }?.let { kind ->
                return if (userId != null) BusinessNotice(userId, kind) else Ignored
            }
            val roomId = data[ROOM_ID]?.takeIf { it.isNotBlank() }
            val eventId = data[EVENT_ID]?.takeIf { it.isNotBlank() }
            return when {
                roomId != null && eventId != null -> Event(userId, roomId, eventId)
                eventId == null && userId != null -> CountChanged(userId)
                else -> Ignored
            }
        }
    }
}
