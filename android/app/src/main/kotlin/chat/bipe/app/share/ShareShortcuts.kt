// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.bipe.app.MainActivity
import chat.bipe.app.R
import chat.bipe.app.chatlist.ChatSummary

/**
 * Sharing shortcuts of spec share-to-chat: the most recent chats of the current account, so the system share sheet
 * offers them (res/xml/shortcuts.xml, share-target). Conversation shortcuts with avatars come with bipe-chat/bipe#771.
 */
object ShareShortcuts {
    /** Equal to the category in res/xml/shortcuts.xml. */
    private const val CATEGORY = "chat.bipe.app.category.SHARE_TARGET"
    private const val PREFIX = "share|"
    const val COUNT = 4

    data class Target(val accountUserId: String, val roomId: String)

    /** The chat of a shortcut ID, or null for any other ID. */
    fun target(shortcutId: String): Target? {
        if (!shortcutId.startsWith(PREFIX)) return null
        val parts = shortcutId.removePrefix(PREFIX).split('|', limit = 2)
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return Target(parts[0], parts[1])
    }

    private fun id(accountUserId: String, roomId: String) = "$PREFIX$accountUserId|$roomId"

    /** Replaces the shortcuts with the newest chats of this account, in room list order. */
    fun update(context: Context, accountUserId: String, chats: List<ChatSummary>) {
        val newest = chats.filter { it.name.isNotBlank() }.take(COUNT)
        val wanted = newest.map { id(accountUserId, it.roomId) }.toSet()
        runCatching {
            val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) && it !in wanted }
            if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
            newest.forEachIndexed { rank, chat ->
                val shortcut = ShortcutInfoCompat.Builder(context, id(accountUserId, chat.roomId))
                    .setShortLabel(chat.name)
                    .setLongLived(true)
                    .setRank(rank)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setCategories(setOf(CATEGORY))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        }
    }

    /** No account left on the phone: the shortcuts go. */
    fun clear(context: Context) {
        runCatching {
            val ids = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(PREFIX) }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }
    }
}
