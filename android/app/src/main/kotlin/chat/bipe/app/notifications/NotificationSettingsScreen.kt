// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountList
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen
import java.time.Instant

/** Spec notification-settings: the Notifications group of Settings. Every row says its current value. */
@Composable
fun NotificationSettingsScreen(session: AccountSession, api: BipeApiClient, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BipeApplication
    val scope = rememberCoroutineScope()
    val model = remember(session) { NotificationSettingsModel(session, api, app.push, app.notifications, scope) }
    val askPermission = rememberNotificationPermission(app.push)
    DisposableEffect(model) {
        model.appear()
        onDispose { model.close() }
    }
    // Back from the system settings, MainActivity.onResume reads the permission again (push.appBecameActive).
    val state by model.state.collectAsState()
    val phoneEnabled by model.phoneEnabled.collectAsState(initial = true)
    val permission by app.push.permission.collectAsState()
    val colors = BipeTheme.colors
    val accountName = remember(session) { AccountList(context).name(session.userId) ?: session.userId }
    var showsWorkHours by rememberSaveable { mutableStateOf(false) }
    if (showsWorkHours) {
        BackHandler { showsWorkHours = false }
        WorkHoursScreen(session, api, app.notifications, onSaved = model::workHoursSaved, onBack = { showsWorkHours = false })
        return
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.NOTIFICATION_SETTINGS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.bipe_settings_notifications), onBack)
            if (!phoneEnabled) {
                // The rows below stay editable: they are account rules and apply on every device.
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.bipe_settings_notifications_off), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                    Text(stringResource(R.string.bipe_notifications_permission_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    Button(onClick = {
                        // Action enablePhone: asks once, else opens the app's system settings.
                        if (permission == PushRegistration.Permission.NOT_ASKED) {
                            askPermission()
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }) {
                        Text(stringResource(R.string.bipe_notifications_enable))
                    }
                }
            }
            SectionTitle(stringResource(R.string.bipe_notifications_notify_about))
            // ADR 0141: no mentions mode in a DM. An older mentions rule still shows until changed.
            val chatOptions = if (state.chatsMode == NotificationMode.MENTIONS) NotificationMode.entries else listOf(NotificationMode.ALL, NotificationMode.OFF)
            ChoiceRow(
                stringResource(R.string.bipe_notifications_chats),
                stringResource(state.chatsMode.label),
                chatOptions.map { stringResource(it.label) to it },
                enabled = state.isLoaded,
                onSelect = model::setChatsMode,
            )
            ChoiceRow(
                stringResource(R.string.bipe_notifications_groups),
                stringResource(state.groupsMode.label),
                NotificationMode.entries.map { stringResource(it.label) to it },
                enabled = state.isLoaded,
                onSelect = model::setGroupsMode,
            )
            if (state.groupsMode == NotificationMode.MENTIONS && state.mentionsMayMiss) {
                Text(
                    stringResource(R.string.bipe_group_screen_notification_settings_mentions_only_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            SwitchRow(stringResource(R.string.bipe_notifications_whole_group), state.wholeGroupMentions, state.isLoaded, model::setWholeGroupMentions)
            SwitchRow(stringResource(R.string.bipe_notifications_invites), state.invites, state.isLoaded, model::setInvites)

            SectionTitle(stringResource(R.string.bipe_notifications_sound))
            val soundLabel = stringResource(state.sound.label)
            ChoiceRow(
                stringResource(R.string.bipe_notifications_message_sound),
                soundLabel,
                NotificationSound.entries.map { stringResource(it.label) to it },
                enabled = true,
                stateDescription = stringResource(R.string.bipe_notifications_tone_current, soundLabel),
                onSelect = model::setSound,
            )
            // bipe-chat/bipe#511: per account on this phone.
            SwitchRow(stringResource(R.string.bipe_notifications_preview), state.preview, true, model::setPreview)
            Text(
                stringResource(R.string.bipe_notifications_preview_footer),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.bipe_notifications_account_footer, accountName),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            // Spec work-hours (ADR 0138 rule 4): company accounts only.
            if (state.isCompany) {
                val hours = state.workHours
                val value = when {
                    hours == null || !hours.enabled -> null
                    hours.isOpen(Instant.now()) -> stringResource(R.string.bipe_work_hours_open_now)
                    else -> stringResource(R.string.bipe_work_hours_closed_now)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().clickable { showsWorkHours = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.bipe_work_hours_title), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                    if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                }
                Text(
                    stringResource(R.string.bipe_work_hours_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // Spec news (ADR 0117): hidden until the API answered.
            if (state.news != NotificationSettingsModel.News.UNKNOWN) {
                SwitchRow(stringResource(R.string.bipe_settings_news), state.news == NotificationSettingsModel.News.ON, !state.newsBusy, model::setNews)
                Text(
                    stringResource(R.string.bipe_settings_news_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    state.pendingGroupsMode?.let {
        AlertDialog(
            onDismissRequest = model::cancelGroupsMode,
            title = { Text(stringResource(R.string.bipe_notifications_mismatch_title)) },
            text = { Text(stringResource(R.string.bipe_notifications_mismatch_body)) },
            confirmButton = { TextButton(onClick = model::confirmGroupsMode) { Text(stringResource(R.string.bipe_action_continue)) } },
            dismissButton = { TextButton(onClick = model::cancelGroupsMode) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    if (state.showsError || state.showsToneError || state.showsNewsError) {
        val message = when {
            state.showsToneError -> R.string.bipe_notifications_tone_error
            state.showsNewsError -> R.string.bipe_settings_news_failed
            else -> R.string.bipe_error_generic
        }
        AlertDialog(
            onDismissRequest = model::dismissErrors,
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = model::dismissErrors) { Text(stringResource(R.string.bipe_action_continue)) } },
        )
    }
}

val NotificationSound.label: Int
    get() = when (this) {
        NotificationSound.PHONE -> R.string.bipe_notifications_tone_system
        NotificationSound.SOFT -> R.string.bipe_notifications_tone_soft
        NotificationSound.SILENT -> R.string.bipe_notifications_tone_silent
    }

@Composable
internal fun Header(title: String, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = stringResource(R.string.bipe_a11y_back), tint = colors.glyphPrimary)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = BipeTheme.colors.accentInk,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    value: String,
    options: List<Pair<String, T>>,
    enabled: Boolean,
    stateDescription: String = value,
    onSelect: (T) -> Unit,
) {
    val colors = BipeTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { this.stateDescription = stateDescription },
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, option) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, isOn: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = BipeTheme.colors.inkPrimary, modifier = Modifier.weight(1f))
        Switch(checked = isOn, onCheckedChange = onChange, enabled = enabled)
    }
}
