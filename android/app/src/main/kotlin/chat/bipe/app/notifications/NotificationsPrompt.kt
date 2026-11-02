// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import chat.bipe.app.account.AccountSession
import chat.bipe.app.timeline.TimelineMessage

/**
 * Opens the system permission dialog (Android 13 and later). Older Android versions allow notifications at
 * install, so there is nothing to ask.
 */
@Composable
fun rememberNotificationPermission(push: PushRegistration, onResult: (Boolean) -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        push.permissionAsked()
        onResult(granted)
    }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            push.appBecameActive()
            onResult(true)
        }
    }
}

/**
 * Spec notifications-prompt: the entry notifications of chat-list-prompt (bipe-chat/bipe#894). This class keeps its
 * memory on this phone; the chat list shows the card (accountAsks).
 */
class NotificationsPromptMemory(context: Context) {
    private val prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    /** True after Ligar notificações or Agora não. The card never comes back on this phone. */
    val isDone: Boolean get() = prefs.getBoolean(KEY_SHOWN, false)

    fun markDone() {
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
    }

    /** Trigger messageSent: this account sent a message on this phone. */
    fun messageSent(userId: String): Boolean = prefs.getBoolean(sentKey(userId), false)

    fun markMessageSent(userId: String) {
        prefs.edit().putBoolean(sentKey(userId), true).apply()
    }

    private fun sentKey(userId: String) = "messageSent_$userId"
}

/** Trigger messageSent (registries/onboarding.json): an own message shows as sent in this chat. */
@Composable
fun NotificationsPromptTrigger(session: AccountSession, roomId: String, messages: List<TimelineMessage>) {
    val context = LocalContext.current
    val memory = remember { NotificationsPromptMemory(context.applicationContext) }
    val openedAt = remember(roomId) { System.currentTimeMillis() }
    // Own messages sent since the chat opened: history does not count.
    val sentSinceOpen = messages.any { it.isOwn && it.sendState == TimelineMessage.SendState.SENT && it.sentAt >= openedAt }
    LaunchedEffect(sentSinceOpen) {
        if (sentSinceOpen) memory.markMessageSent(session.userId)
    }
}

private const val KEY_SHOWN = "notificationsPromptShown"
