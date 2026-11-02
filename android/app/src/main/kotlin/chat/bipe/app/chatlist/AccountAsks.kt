// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import chat.bipe.app.BipeApplication
import chat.bipe.app.account.AccountSession
import chat.bipe.app.notifications.NotificationsPromptMemory
import chat.bipe.app.notifications.PushRegistration
import chat.bipe.app.notifications.rememberNotificationPermission
import chat.bipe.app.settings.OsTheftPromptMemory
import chat.bipe.app.settings.OsTheftProtection
import chat.bipe.shared.generated.BipeChatListPrompt
import chat.bipe.shared.generated.BipeOnboardingPrompt

/**
 * Spec chat-list-prompt entries notifications, osTheftProtection and photo (bipe-chat/bipe#894, MZ 2 Nov 2026): the
 * asks that had a place of their own before (the notifications sheet, the Você cards). Each keeps the condition of
 * its source spec; chat-list-prompt picks which one shows. The caller leaves composition while another screen is
 * open (for example the profile editor), so each return reads the conditions again.
 */
@Composable
fun accountAsks(session: AccountSession, openProfileEdit: () -> Unit): Map<BipeChatListPrompt, @Composable () -> Unit> {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val photoPrefs = remember { context.getSharedPreferences("bipe_photo_prompt", Context.MODE_PRIVATE) }
    val photoKey = "dismissed_${session.userId}"
    val osTheft = remember { OsTheftPromptMemory(context.applicationContext) }
    val notifications = remember { NotificationsPromptMemory(context.applicationContext) }
    var changed by remember { mutableIntStateOf(0) }
    var hasNoPhoto by remember(session) { mutableStateOf(false) }
    LaunchedEffect(session) {
        app.push.appBecameActive()
        // Only a profile that loaded without a photo shows the card; a failed read shows nothing.
        hasNoPhoto = runCatching { session.client.avatarUrl() }.map { it == null }.getOrDefault(false)
        changed++
    }
    val askPermission = rememberNotificationPermission(app.push) {
        notifications.markDone()
        changed++
    }
    val finishOsTheft: () -> Unit = {
        osTheft.markDone()
        changed++
    }
    // Read again after each change; changed is part of the key so the map follows it.
    return remember(changed, hasNoPhoto, session) {
        buildMap<BipeChatListPrompt, @Composable () -> Unit> {
            if (!notifications.isDone && notifications.messageSent(session.userId) &&
                app.push.permission.value == PushRegistration.Permission.NOT_ASKED
            ) {
                put(BipeChatListPrompt.NOTIFICATIONS) {
                    AskCard(BipeOnboardingPrompt.NOTIFICATIONS, open = askPermission, dismiss = {
                        notifications.markDone()
                        changed++
                    })
                }
            }
            if (!osTheft.isDone && osTheft.isDue(session.userId)) {
                put(BipeChatListPrompt.OS_THEFT_PROTECTION) {
                    AskCard(BipeOnboardingPrompt.OS_THEFT_PROTECTION, note = stringResource(OsTheftProtection.pathText), open = {
                        finishOsTheft()
                        OsTheftProtection.openSettings(context)
                    }, dismiss = finishOsTheft)
                }
            }
            if (hasNoPhoto && !photoPrefs.getBoolean(photoKey, false)) {
                put(BipeChatListPrompt.PHOTO) {
                    AskCard(BipeOnboardingPrompt.PHOTO, open = openProfileEdit, dismiss = {
                        photoPrefs.edit().putBoolean(photoKey, true).apply()
                        changed++
                    })
                }
            }
        }
    }
}

/** One card of [accountAsks] in the shape of every chat list prompt. */
@Composable
private fun AskCard(prompt: BipeOnboardingPrompt, note: String? = null, open: () -> Unit, dismiss: () -> Unit) {
    PromptCard(
        title = stringResource(prompt.title),
        body = prompt.body?.let { stringResource(it) },
        primary = PromptAction(stringResource(prompt.primary.title), open),
        texts = prompt.text.map { PromptAction(stringResource(it.title), dismiss) },
        note = note,
    )
}
