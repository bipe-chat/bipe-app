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

    /** Nothing this phone can act on. */
    data object Ignored : BipePushMessage

    companion object {
        const val ROOM_ID = "room_id"
        const val EVENT_ID = "event_id"
        const val ACCOUNT_ROUTE = "account_route"

        fun parse(data: Map<String, String>, accountFor: (String) -> String?): BipePushMessage {
            val userId = data[ACCOUNT_ROUTE]?.takeIf { it.isNotBlank() }?.let(accountFor)
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
