// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.*
import chat.bipe.shared.generated.BipeReactions
import java.awt.Robot
import java.awt.event.KeyEvent
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException

internal sealed interface TimelinePopup {
    data class MessageMenu(val rowId: String) : TimelinePopup
    data class Menu(val rowId: String) : TimelinePopup
    data class Picker(val rowId: String) : TimelinePopup
    data class Senders(val rowId: String, val key: String) : TimelinePopup
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable internal fun MessageReactions(model: DesktopTimeline, message: MessageRow, popup: TimelinePopup?,
                                         show: (TimelinePopup?) -> Unit, s: DesktopCatalog, colors: DesktopColors) {
    if (message.eventId == null) return
    val action by model.reactions.phase.collectAsState()
    val enabled = action !is ReactionActionPhase.Sending
    val peopleLabel = s.text("bipe_app_reactions_title")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        message.reactions.forEach { reaction ->
            val count = NumberFormat.getIntegerInstance().format(reaction.count)
            val label = s.text("bipe_app_reaction_label", reaction.key, count)
            val openPeople = { show(TimelinePopup.Senders(message.id, reaction.key)) }
            ContextMenuArea(items = { listOf(ContextMenuItem(peopleLabel, onClick = openPeople)) }) {
                Surface(shape = CircleShape, color = colors[if (reaction.includesOwn) "accent.wash" else "surface.base"],
                    contentColor = colors[if (reaction.includesOwn) "accent.ink" else "ink.primary"],
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = label
                        selected = reaction.includesOwn
                        customActions = listOf(CustomAccessibilityAction(peopleLabel) { openPeople(); true })
                    }.combinedClickable(enabled = enabled, onClick = { model.reactions.toggle(message.id, reaction.key) },
                        onLongClickLabel = peopleLabel, onLongClick = openPeople)) {
                    Text(BipeReactions.keyText(reaction.key) + " " + count, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Box {
            TextButton(onClick = { show(TimelinePopup.Menu(message.id)) }, enabled = enabled) { Text(peopleLabel) }
            DropdownMenu(expanded = popup == TimelinePopup.Menu(message.id), onDismissRequest = { show(null) }) {
                Row {
                    BipeReactions.quickKeys.forEach { key -> TextButton(onClick = {
                        show(null); model.reactions.toggle(message.id, key)
                    }, enabled = enabled) { Text(key) } }
                }
                DropdownMenuItem(text = { Text(s.text("bipe_app_message_action_more_reactions")) },
                    onClick = { show(TimelinePopup.Picker(message.id)) })
            }
        }
    }
}

@Composable internal fun ReactionDialog(model: DesktopTimeline, popup: TimelinePopup?, close: () -> Unit, s: DesktopCatalog) {
    when (popup) {
        is TimelinePopup.Picker -> key(model, popup) {
            val focus = remember { FocusRequester() }
            AlertDialog(onDismissRequest = close, title = { Text(s.text("bipe_app_message_action_more_reactions")) },
                text = { OutlinedTextField("", { input ->
                    // The shared contract sends the first extended grapheme from the system picker, not a code unit.
                    Regex("\\X").find(input)?.value?.takeIf { it.isNotBlank() }?.let { key ->
                        close(); model.reactions.toggle(popup.rowId, key)
                    }
                }, singleLine = true, modifier = Modifier.focusRequester(focus),
                    label = { Text(s.text("bipe_app_message_action_more_reactions")) }) },
                confirmButton = {}, dismissButton = { TextButton(onClick = close) { Text(s.text("bipe_app_cancel")) } })
            LaunchedEffect(popup) {
                focus.requestFocus()
                withFrameNanos { }
                // Windows' own emoji panel types into the focused Compose field. No custom emoji catalogue.
                runCatching {
                    val robot = Robot()
                    robot.keyPress(KeyEvent.VK_WINDOWS)
                    try { robot.keyPress(KeyEvent.VK_PERIOD); robot.keyRelease(KeyEvent.VK_PERIOD) }
                    finally { robot.keyRelease(KeyEvent.VK_WINDOWS) }
                }
            }
        }
        is TimelinePopup.Senders -> key(model, popup) {
            val people by produceState<LoadPhase<List<ReactionPerson>>>(LoadPhase.Loading) {
                value = try { LoadPhase.Ready(model.reactionPeople(popup.rowId, popup.key, s.text("bipe_app_you"))) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { LoadPhase.Failed }
            }
            AlertDialog(onDismissRequest = close, title = { Text(s.text("bipe_app_reactions_title")) }, text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(BipeReactions.keyText(popup.key), style = MaterialTheme.typography.headlineMedium)
                    when (val state = people) {
                        LoadPhase.Loading -> Text(s.text("bipe_web_loading"))
                        LoadPhase.Failed -> Text(s.text("bipe_error_generic"))
                        is LoadPhase.Ready -> state.value.forEach { Text(it.name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = close) { Text(s.text("bipe_app_close")) } })
        }
        else -> Unit
    }
}
