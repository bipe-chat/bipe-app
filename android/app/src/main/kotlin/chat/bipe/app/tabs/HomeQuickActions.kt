// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.tabs

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import chat.bipe.app.MainActivity
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/**
 * Spec app-tabs, action homeQuickAction: the shortcuts of a long press on the app icon (bipe-chat/bipe#1241).
 * Dynamic, because the application ID differs per build and a static shortcut names it literally, and because they go
 * when the phone has no account. They rank above the share shortcuts, so the launcher shows them first.
 */
object HomeQuickActions {
    enum class Action(val id: String, val intentAction: String, @StringRes val label: Int, val icon: PhosphorIcon) {
        NEW_CHAT("quick|newChat", "chat.bipe.app.action.QUICK_NEW_CHAT", R.string.bipe_action_start_chat, PhosphorIcon.CHAT_TEXT),
        SEARCH("quick|search", "chat.bipe.app.action.QUICK_SEARCH", R.string.bipe_app_tab_search, PhosphorIcon.MAGNIFYING_GLASS),
        MY_QR_CODE("quick|myQrCode", "chat.bipe.app.action.QUICK_MY_QR_CODE", R.string.bipe_profile_qr_title, PhosphorIcon.QR_CODE),
        SWITCH_ACCOUNT("quick|switchAccount", "chat.bipe.app.action.QUICK_SWITCH_ACCOUNT", R.string.bipe_a11y_switch_account, PhosphorIcon.USER_CIRCLE),
    }

    /** The first rank that is free for other shortcuts. */
    val COUNT = Action.entries.size

    /** The action of a shortcut's intent, or null for any other intent. */
    fun from(intent: Intent?): Action? = Action.entries.firstOrNull { it.intentAction == intent?.action }

    /** Switch account shows only with a second account on this phone. */
    fun update(context: Context, severalAccounts: Boolean) {
        runCatching {
            Action.entries.forEachIndexed { rank, action ->
                if (action == Action.SWITCH_ACCOUNT && !severalAccounts) {
                    ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(action.id))
                    return@forEachIndexed
                }
                val label = context.getString(action.label)
                val shortcut = ShortcutInfoCompat.Builder(context, action.id)
                    .setShortLabel(label)
                    .setRank(rank)
                    .setIcon(IconCompat.createWithResource(context, action.icon.drawable))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(action.intentAction))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            }
        }
    }

    /** No account left on the phone: the shortcuts go. */
    fun clear(context: Context) {
        runCatching { ShortcutManagerCompat.removeDynamicShortcuts(context, Action.entries.map { it.id }) }
    }
}
