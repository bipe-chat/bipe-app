// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.ForwardActions
import chat.bipe.desktop.chat.MessageRow
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.media.BipeForwardMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface ForwardPhase {
    data object Loading : ForwardPhase
    data object Unavailable : ForwardPhase
    data class Picking(val picker: ForwardActions.Picker, val picked: Set<String> = emptySet(), val overLimit: Boolean = false) : ForwardPhase
    data object Sending : ForwardPhase
}

@Composable internal fun ForwardDialog(actions: ForwardActions, ids: Set<String>, s: DesktopCatalog, close: () -> Unit,
                                       sent: (Int, List<String>) -> Unit) {
    var phase by remember(actions, ids) { mutableStateOf<ForwardPhase>(ForwardPhase.Loading) }
    var query by remember(actions, ids) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(actions, ids) {
        phase = try { ForwardPhase.Picking(actions.prepare(ids)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ForwardPhase.Unavailable }
    }
    AlertDialog(
        onDismissRequest = { if (phase != ForwardPhase.Sending) close() },
        title = { Text(s.text("bipe_forward_title")) },
        text = {
            when (val current = phase) {
                ForwardPhase.Loading, ForwardPhase.Sending -> CircularProgressIndicator()
                ForwardPhase.Unavailable -> Text(s.text("bipe_error_generic"))
                is ForwardPhase.Picking -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query, { query = it }, label = { Text(s.text("bipe_app_search_placeholder")) }, singleLine = true)
                    val term = ForwardActions.fold(query.trim())
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(current.picker.chats.filter { ForwardActions.fold(it.name).contains(term) }, key = { it.id }) { chat ->
                            val picked = chat.id in current.picked
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(picked, role = Role.Checkbox) {
                                phase = when {
                                    picked -> current.copy(picked = current.picked - chat.id, overLimit = false)
                                    current.picked.size < current.picker.limit -> current.copy(picked = current.picked + chat.id, overLimit = false)
                                    else -> current.copy(overLimit = true)
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(picked, onCheckedChange = null)
                                Text(chat.name, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    if (current.overLimit) Text(if (current.picker.limit == BipeRegistries.FORWARD_CHATS_MANY_TIMES)
                        s.text("bipe_forward_limit_many") else s.text("bipe_forward_limit", current.picker.limit.toString()))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = phase != ForwardPhase.Sending) { Text(s.text("bipe_app_cancel")) }
        },
        confirmButton = {
            val current = phase as? ForwardPhase.Picking
            TextButton(enabled = current?.picked?.isNotEmpty() == true, onClick = {
                val pick = phase as? ForwardPhase.Picking ?: return@TextButton
                phase = ForwardPhase.Sending
                scope.launch {
                    val failed = try { actions.send(pick.picker, pick.picked) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { phase = ForwardPhase.Unavailable; return@launch }
                    sent(ids.size, failed)
                }
            }) { Text(s.text("bipe_forward_action")) }
        },
    )
}

@Composable internal fun SelectionBar(count: Int, canForward: Boolean, canDelete: Boolean, s: DesktopCatalog,
                                      forward: () -> Unit, delete: () -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.text("bipe_select_count", count.toString()), Modifier.weight(1f))
        TextButton(onClick = forward, enabled = canForward) { Text(s.text("bipe_forward_action")) }
        TextButton(onClick = delete, enabled = canDelete) { Text(s.text("bipe_app_message_action_delete")) }
        TextButton(onClick = close) { Text(s.text("bipe_app_cancel")) }
    }
}

@Composable internal fun SelectionMessage(message: MessageRow, selected: Boolean, enabled: Boolean, s: DesktopCatalog,
                                          colors: DesktopColors, toggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(selected, enabled = enabled || selected,
        role = Role.Checkbox, onValueChange = { toggle() }).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (enabled || selected) Checkbox(selected, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(if (message.own) s.text("bipe_app_you") else message.sender, style = MaterialTheme.typography.labelMedium, color = colors["accent.ink"])
            Text(message.body)
        }
    }
}

@Composable internal fun SelectionDeleteDialog(s: DesktopCatalog, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(s.text("bipe_select_delete_title")) },
        text = { Text(s.text("bipe_select_delete_body")) },
        dismissButton = { TextButton(onClick = close) { Text(s.text("bipe_app_cancel")) } },
        confirmButton = { TextButton(onClick = confirm) { Text(s.text("bipe_app_message_action_delete")) } })
}

@Composable internal fun ForwardSource(source: BipeForwardMark.Source, s: DesktopCatalog, colors: DesktopColors) {
    val key = when (source) {
        BipeForwardMark.Source.FORWARDED -> "bipe_forward_line"
        BipeForwardMark.Source.FORWARDED_MANY_TIMES -> "bipe_forward_line_many"
        BipeForwardMark.Source.RECORDED -> "bipe_voice_source_recorded"
        BipeForwardMark.Source.FILE -> "bipe_voice_source_file"
    }
    Text(s.text(key), style = MaterialTheme.typography.labelSmall, color = colors["ink.secondary"])
}
