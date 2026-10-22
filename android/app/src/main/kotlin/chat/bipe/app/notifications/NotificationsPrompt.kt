// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.timeline.TimelineMessage
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.generated.BipeScreen

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
 * Spec notifications-prompt: after the first own message shows as sent, a sheet asks whether the person wants
 * to know when the reply comes. Only while the system permission was never asked, and once per phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsPrompt(session: AccountSession, roomId: String, messages: List<TimelineMessage>) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val prefs = remember { context.getSharedPreferences("push", Context.MODE_PRIVATE) }
    val openedAt = remember(roomId) { System.currentTimeMillis() }
    var isShown by remember { mutableStateOf(false) }
    var isAsking by remember { mutableStateOf(false) }
    var recipientName by remember { mutableStateOf<String?>(null) }
    // Own messages sent since the chat opened: history does not count.
    val newSent = messages.count { it.isOwn && it.sendState == TimelineMessage.SendState.SENT && it.sentAt >= openedAt }

    fun close() {
        prefs.edit().putBoolean(KEY_SHOWN, true).apply()
        isShown = false
    }

    val askPermission = rememberNotificationPermission(app.push) {
        isAsking = false
        close()
    }

    LaunchedEffect(newSent) {
        if (newSent == 0 || isShown || prefs.getBoolean(KEY_SHOWN, false)) return@LaunchedEffect
        app.push.appBecameActive()
        if (app.push.permission.value != PushRegistration.Permission.NOT_ASKED) return@LaunchedEffect
        val info = runCatching { session.client.getRoom(roomId)?.roomInfo() }.getOrNull()
        recipientName = info?.takeIf { it.isDm }?.displayName
        isShown = true
    }

    if (!isShown) return
    val colors = BipeTheme.colors
    val prompt = BipeOnboardingPrompt.NOTIFICATIONS
    ModalBottomSheet(onDismissRequest = ::close, containerColor = colors.surfaceRaised) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(BipeScreen.NOTIFICATIONS_PROMPT.id),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(painterResource(PhosphorIcon.BELL.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(48.dp))
            val name = recipientName
            Text(
                if (name != null) stringResource(R.string.bipe_notifications_prompt_title, name) else stringResource(R.string.bipe_notifications_prompt_title_generic),
                style = MaterialTheme.typography.titleLarge,
                color = colors.inkPrimary,
                textAlign = TextAlign.Center,
            )
            prompt.body?.let { body ->
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            // Action allow: the system dialog; either way the sheet closes for good.
            Button(onClick = {
                isAsking = true
                askPermission()
            }, enabled = !isAsking, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(prompt.primary.title))
            }
            // Action notNow.
            prompt.text.forEach { button ->
                TextButton(onClick = ::close, enabled = !isAsking) {
                    Text(stringResource(button.title))
                }
            }
        }
    }
}

private const val KEY_SHOWN = "notificationsPromptShown"
