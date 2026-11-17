// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.chat.*
import kotlinx.coroutines.CancellationException

@Composable internal fun RoomNotificationDialog(runtime: MatrixAccountRuntime, room: String, s: DesktopCatalog, close: () -> Unit) {
    var attempt by remember(runtime, room) { mutableIntStateOf(0) }
    val page by key(runtime, room, attempt) { produceState<LoadPhase<RoomNotifications>>(LoadPhase.Loading) {
        value = try { LoadPhase.Ready(RoomNotifications.open(runtime, room)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LoadPhase.Failed }
    } }
    AlertDialog(onDismissRequest = close, title = { Text(s.text("bipe_settings_notifications")) }, text = {
        when (val load = page) {
            LoadPhase.Loading -> Text(s.text("bipe_web_loading"))
            LoadPhase.Failed -> Column {
                Text(s.text("bipe_app_notify_error"))
                TextButton(onClick = { attempt++ }) { Text(s.text("bipe_app_retry")) }
            }
            is LoadPhase.Ready -> NotificationChoices(load.value, s)
        }
    }, confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } })
}

@Composable private fun NotificationChoices(model: RoomNotifications, s: DesktopCatalog) {
    val phase by model.phase.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val state = phase) {
            NotifyPhase.Loading -> Text(s.text("bipe_web_loading"))
            NotifyPhase.Failed -> {
                Text(s.text("bipe_app_notify_error"))
                TextButton(onClick = { model.retry() }) { Text(s.text("bipe_app_retry")) }
            }
            is NotifyPhase.Ready -> {
                state.snapshot.options.forEach { option ->
                    val selected = state.snapshot.current == option
                    val enabled = state.save !is NotifySave.Sending && option != NotifyMode.LEGACY
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton,
                        onClick = { model.choose(option) }), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected, onClick = null, enabled = enabled)
                        Column {
                            Text(s.text(option.title))
                            if (option == NotifyMode.QUIET) Text(s.text("bipe_app_notify_quiet_footer"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (state.save is NotifySave.Sending) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.save is NotifySave.Failed) {
                    Text(s.text("bipe_app_notify_error"))
                    TextButton(onClick = { model.retry() }) { Text(s.text("bipe_app_retry")) }
                }
            }
        }
    }
}
