// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.NotificationSettings
import uniffi.matrix_sdk_base.EncryptionState

/** Spec chat-notifications (bipe-shared product/screens/chat-notifications.json). */
class ChatNotificationsModel(private val session: AccountSession, val roomId: String, private val scope: CoroutineScope) {
    data class State(
        val isDm: Boolean = true,
        val mode: NotificationMode = NotificationMode.ALL,
        val isDefault: Boolean = true,
        val defaultMode: NotificationMode = NotificationMode.ALL,
        val mentionsMayMiss: Boolean = false,
        val isLoaded: Boolean = false,
        val showsError: Boolean = false,
    ) {
        /** DM: all and off only (ADR 0141). A DM with an older mentions rule keeps it on the list until changed. */
        val options: List<NotificationMode>
            get() = if (isDm && mode != NotificationMode.MENTIONS) listOf(NotificationMode.ALL, NotificationMode.OFF) else NotificationMode.entries
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var settings: NotificationSettings? = null
    private var isEncrypted = true
    private var isOneToOne = true

    /** Action appear. */
    fun appear() {
        scope.launch {
            val room = runCatching { session.client.getRoom(roomId) }.getOrNull() ?: return@launch
            val info = runCatching { room.roomInfo() }.getOrNull() ?: return@launch
            // Push rules count members: a one-to-one room has two.
            isOneToOne = info.activeMembersCount == 2uL
            isEncrypted = runCatching { room.latestEncryptionState() }.getOrNull() != EncryptionState.NOT_ENCRYPTED
            _state.update { it.copy(isDm = info.isDm) }
            val settings = session.client.getNotificationSettings()
            this@ChatNotificationsModel.settings = settings
            load(settings)
        }
    }

    /** Action useDefault: removes the chat's own rule, so it follows Settings. */
    fun useDefault() = write { it.restoreDefaultRoomNotificationMode(roomId) }

    /** Action setMode. Leaving off uses unmuteRoom, so the room goes back to its previous mode. */
    fun setMode(mode: NotificationMode) {
        val wasOff = _state.value.mode == NotificationMode.OFF && !_state.value.isDefault
        write { settings ->
            if (wasOff && mode != NotificationMode.OFF) settings.unmuteRoom(roomId, isEncrypted, isOneToOne)
            settings.setRoomNotificationMode(roomId, mode.sdkMode)
        }
    }

    fun dismissError() {
        _state.update { it.copy(showsError = false) }
    }

    private suspend fun load(settings: NotificationSettings) {
        val room = runCatching { settings.getRoomNotificationSettings(roomId, isEncrypted, isOneToOne) }.getOrNull()
        val default = NotificationMode.of(settings.getDefaultRoomNotificationMode(isEncrypted, isOneToOne))
        val mayMiss = isEncrypted && !settings.canPushEncryptedEventToDevice()
        _state.update {
            it.copy(
                mode = room?.let { r -> NotificationMode.of(r.mode) } ?: it.mode,
                isDefault = room?.isDefault ?: it.isDefault,
                defaultMode = default,
                mentionsMayMiss = mayMiss,
                isLoaded = true,
            )
        }
    }

    /** A failure puts the old value back and shows bipe_error_generic. */
    private fun write(change: suspend (NotificationSettings) -> Unit) {
        val settings = settings ?: return
        scope.launch {
            if (runCatching { change(settings) }.isFailure) _state.update { it.copy(showsError = true) }
            load(settings)
        }
    }
}

/** Spec chat-notifications: every message, only mentions (groups) or silenced, or the Settings default. */
@Composable
fun ChatNotificationsScreen(session: AccountSession, roomId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { ChatNotificationsModel(session, roomId, scope) }
    LaunchedEffect(model) { model.appear() }
    val state by model.state.collectAsState()
    val colors = BipeTheme.colors
    val title = if (state.isDm) R.string.bipe_dm_notifications_custom_title else R.string.bipe_group_screen_room_notification_settings_room_custom_settings_title

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_NOTIFICATIONS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(stringResource(title), onBack)
            Option(
                stringResource(R.string.bipe_app_notification_mode_default, stringResource(state.defaultMode.label)),
                isSelected = state.isDefault,
                enabled = state.isLoaded,
                onClick = model::useDefault,
            )
            state.options.forEach { mode ->
                Option(stringResource(mode.label), isSelected = !state.isDefault && state.mode == mode, enabled = state.isLoaded) {
                    model.setMode(mode)
                }
            }
            val disclaimer = when {
                state.isDm && state.mode == NotificationMode.MENTIONS && !state.isDefault -> R.string.bipe_dm_notifications_mentions_disclaimer
                !state.isDm && state.mode == NotificationMode.MENTIONS && state.mentionsMayMiss -> R.string.bipe_group_screen_room_notification_settings_mentions_only_disclaimer
                else -> null
            }
            disclaimer?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
            }
        }
    }
    if (state.showsError) {
        AlertDialog(
            onDismissRequest = model::dismissError,
            text = { Text(stringResource(R.string.bipe_error_generic)) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.bipe_action_continue)) } },
        )
    }
}

@Composable
private fun Option(title: String, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
        if (isSelected) {
            Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
        }
    }
}
