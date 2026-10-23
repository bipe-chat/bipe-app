// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

/** Notification kind chooses a destination; an invite never enters an unjoined timeline. */
enum class BipeNotificationDestination {
    CHAT, REQUESTS, GROUPS, INBOX;

    companion object {
        fun fromKind(kind: String?): BipeNotificationDestination = when (kind) {
            "message" -> CHAT
            "chatRequest" -> REQUESTS
            "groupInvite" -> GROUPS
            else -> INBOX
        }
    }
}

data class BipeNotificationOpen(val target: BipeNotificationTarget, val destination: BipeNotificationDestination) {
    val accountUserId: String get() = target.accountUserId
    val roomId: String get() = target.roomId
}

/** Plain previews only. Callers pass a caption, never a media URL or computed filename. */
object BipeNotificationPreview {
    fun text(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    fun attachment(label: String, caption: String?): String = text(caption)?.let { "$label: $it" } ?: label
}
