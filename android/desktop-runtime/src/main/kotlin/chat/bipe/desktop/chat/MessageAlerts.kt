// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

/**
 * Spec notification-service on a computer (the Mac rule, bipe-chat/bipe#1223): there is no push, so alerts come from
 * this account's own sync, one per chat with its newest message. History at launch stays silent, and the chat open in
 * the focused window gets none. Not thread safe: the UI calls it from one place.
 */
class MessageAlerts(private val startedMs: Long) {
    data class Alert(val row: ChatRow, val newMention: Boolean)
    /** [alerts] may show, after the mode check. [cleared] were read, opened or left the list. */
    data class Update(val alerts: List<Alert>, val cleared: Set<String>)

    private val incoming = mutableMapOf<String, Long>()
    private val mentions = mutableMapOf<String, Long>()
    private val delivered = mutableSetOf<String>()

    fun update(rows: List<ChatRow>, isOpen: (String) -> Boolean): Update {
        val alerts = mutableListOf<Alert>()
        for (row in rows) {
            val new = isNew(row.latestIncoming, incoming[row.id], startedMs, row.unread)
            val newMention = row.mentions > (mentions[row.id] ?: 0)
            row.latestIncoming?.let { incoming[row.id] = it }
            mentions[row.id] = row.mentions
            if (new && !isOpen(row.id)) alerts += Alert(row, newMention)
        }
        val unread = rows.filter { it.hasUnread }.map { it.id }.toSet()
        val open = rows.map { it.id }.filter(isOpen).toSet()
        val cleared = (delivered - unread) + delivered.intersect(open)
        delivered -= cleared
        delivered += alerts.map { it.row.id }
        return Update(alerts, cleared)
    }

    /** The mode check dropped this alert: it was never shown. */
    fun dropped(roomId: String) { delivered -= roomId }

    companion object {
        /** A room seen before compares with its last incoming message; a new room compares with the start of this account. */
        fun isNew(incomingMs: Long?, previousMs: Long?, startedMs: Long, unread: Long): Boolean =
            unread > 0 && incomingMs != null && incomingMs > (previousMs ?: startedMs)

        fun alerts(mode: NotifyMode, newMention: Boolean): Boolean = when (mode) {
            NotifyMode.MUTE -> false
            NotifyMode.QUIET, NotifyMode.LEGACY -> newMention
            NotifyMode.ALL -> true
        }

        /** Spec notification-service body: the chat list preview, but never the undecryptable text. */
        fun content(row: ChatRow, labels: ChatLabels, appName: String, newMessage: String): Pair<String, String> {
            val preview = row.preview?.takeIf { it.isNotBlank() && it != labels.undecryptable && it != labels.unsupported }
            return if (preview == null) appName to newMessage else row.name to preview
        }
    }
}
