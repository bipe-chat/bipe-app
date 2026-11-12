// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.desktop.chat.*
import java.text.NumberFormat
import kotlinx.coroutines.CancellationException

/** One menu lifecycle shared with reaction overlays; no permanent message overflow button. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable internal fun MessageSurface(model: DesktopTimeline, row: MessageRow, seconds: Int, highlighted: Boolean,
                                       popup: TimelinePopup?, show: (TimelinePopup?) -> Unit, s: DesktopCatalog,
                                       colors: DesktopColors, focusComposer: () -> Unit, content: @Composable () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val label = s.text("bipe_message_actions")
    val actions = buildList {
        if (row.canReply) add(ChatRowAction(s.text("bipe_app_message_action_reply"), true) {
            if (model.replies.start(row.id)) focusComposer()
        })
        if (row.kind == MessageRow.Kind.TEXT) add(ChatRowAction(if (seconds > 0)
            s.text("bipe_pause_countdown", NumberFormat.getIntegerInstance().format(seconds)) else s.text("bipe_app_copy"), seconds == 0) {
            model.copyText(row.id)?.let { clipboard.setText(AnnotatedString(it)) }
        })
    }
    val open = { if (actions.isNotEmpty()) { focus.requestFocus(); show(TimelinePopup.MessageMenu(row.id)) } }
    var modifier = Modifier.widthIn(max = 540.dp)
    if (actions.isNotEmpty()) modifier = modifier.focusRequester(focus).focusable().semantics {
        customActions = listOf(CustomAccessibilityAction(label) { open(); true }) + actions.filter { it.enabled }.map { action ->
            CustomAccessibilityAction(action.label) { show(null); action.run(); true }
        }
    }.onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && ((it.key == Key.F10 && it.isShiftPressed) || it.key == Key.Menu)) {
            open(); true
        } else false
    }.onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { event ->
        // Consume before SelectionContainer so its separate Copy menu cannot overlap this message menu.
        if (event.buttons.isSecondaryPressed) { event.changes.forEach { it.consume() }; open() }
    }.combinedClickable(onClick = { focus.requestFocus() }, onLongClick = open, onLongClickLabel = label)
    Box {
        Surface(modifier, shape = RoundedCornerShape(18.dp),
            border = if (highlighted) BorderStroke(2.dp, colors["accent.fill"]) else null,
            color = colors[if (row.own) "bubble.own" else "bubble.other"], contentColor = colors["ink.primary"], content = content)
        DropdownMenu(expanded = popup == TimelinePopup.MessageMenu(row.id) && actions.isNotEmpty(), onDismissRequest = { show(null) }) {
            actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled,
                onClick = { show(null); action.run() }) }
        }
    }
}

@Composable internal fun ReplyQuote(quote: MessageQuote, s: DesktopCatalog, open: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = open)
        .semantics(mergeDescendants = true) {}.padding(vertical = 6.dp, horizontal = 10.dp)) {
        when (val details = quote.details) {
            is MessageQuote.Details.Ready -> {
                Text(details.sender, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MessageQuote.Details.Loading -> Text(s.text("bipe_app_message_reply_loading"), style = MaterialTheme.typography.bodySmall)
            MessageQuote.Details.Unavailable -> Text(s.text("bipe_app_message_reply_unavailable"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable internal fun ComposerReply(model: DesktopTimeline, eventId: String, rows: List<MessageRow>, s: DesktopCatalog) {
    val loaded by produceState(MessageQuote(eventId, MessageQuote.Details.Loading), model, eventId) {
        value = try { model.replyDetails(eventId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MessageQuote(eventId, MessageQuote.Details.Unavailable) }
    }
    val current = rows.firstOrNull { it.originalEventId == eventId }
    val quote = if (current == null) loaded else MessageQuote(eventId,
        MessageQuote.Details.Ready(if (current.own) s.text("bipe_app_you") else current.sender, current.body))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val name = (quote.details as? MessageQuote.Details.Ready)?.sender.orEmpty()
            Text(s.text("bipe_app_message_replying_to", name), style = MaterialTheme.typography.labelMedium)
            val preview = when (val details = quote.details) {
                is MessageQuote.Details.Ready -> details.preview
                MessageQuote.Details.Loading -> s.text("bipe_app_message_reply_loading")
                MessageQuote.Details.Unavailable -> s.text("bipe_app_message_reply_unavailable")
            }
            Text(preview, Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { model.openOriginal(eventId) }
                .padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = model.composer::cancelReply) { Text(s.text("bipe_app_cancel")) }
    }
}
