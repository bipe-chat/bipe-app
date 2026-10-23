// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import java.security.MessageDigest

/** A notification belongs to one account and one room, including rooms shared by several accounts. */
data class BipeNotificationTarget private constructor(val accountUserId: String, val roomId: String) {
    /** Opaque platform grouping key; length prefixes keep account/room boundaries unambiguous. */
    val identifier: String
        get() {
            val value = "bipe.notification.v1|${accountUserId.toByteArray(Charsets.UTF_8).size}:$accountUserId|${roomId.toByteArray(Charsets.UTF_8).size}:$roomId"
            return MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

    companion object {
        /** Missing, malformed or legacy owners never fall back to a different account. */
        fun create(accountUserId: String?, roomId: String?): BipeNotificationTarget? {
            if (accountUserId == null || roomId == null || !accountUserId.startsWith("@")) return null
            val colon = accountUserId.indexOf(':')
            if (colon < 2 || colon == accountUserId.lastIndex) return null
            if (!roomId.startsWith("!") || roomId.length < 2) return null
            if ((accountUserId + roomId).any { it.isWhitespace() || it == '\u0000' }) return null
            return BipeNotificationTarget(accountUserId, roomId)
        }
    }
}
