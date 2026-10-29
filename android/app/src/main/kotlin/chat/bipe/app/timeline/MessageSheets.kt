// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.generated.BipeScreen
import java.text.BreakIterator
import java.text.DateFormat

/** Spec message-actions: the message on top, the quick reactions, then the actions that apply. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionsSheet(model: ChatTimelineModel, row: TimelineRow, onCopy: (String) -> Unit, onHaptic: (BipeHaptic) -> Unit) {
    val colors = BipeTheme.colors
    ModalBottomSheet(onDismissRequest = model::closeActions, containerColor = colors.surfaceRaised) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag(BipeScreen.MESSAGE_ACTIONS.id),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            row.message?.let { message ->
                Text(
                    message.body
                        ?: message.media?.let { stringResource(it.kindLabel) }
                        ?: message.voice?.let { stringResource(R.string.bipe_app_media_voice) }
                        ?: message.senderName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (message.isOwn) colors.bubbleOwn else colors.bubbleOther, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                )
            }
            if (model.showsReactions(row)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    QUICK_REACTIONS.forEach { key ->
                        TextButton(onClick = {
                            onHaptic(BipeHaptic.Tick)
                            model.react(row.id, key)
                        }, modifier = Modifier.weight(1f)) {
                            Text(key, fontSize = 24.sp)
                        }
                    }
                    IconButton(onClick = { model.moreReactions(row.id) }, modifier = Modifier.weight(1f)) {
                        Icon(
                            painterResource(PhosphorIcon.SMILEY.drawable),
                            contentDescription = stringResource(R.string.bipe_app_message_action_more_reactions),
                            tint = colors.glyphSecondary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }
            Column {
                val pauseLeft = rememberPauseSecondsLeft(model, row.id)
                model.actions(row).forEach { action ->
                    // Spec scam-warning money pause: Copiar waits, with the countdown as its label.
                    val paused = action == MessageAction.COPY && pauseLeft > 0
                    val tint = when {
                        action == MessageAction.DELETE -> colors.trustDangerInk
                        paused -> colors.inkDisabled
                        else -> colors.inkPrimary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !paused) {
                                if (action == MessageAction.COPY) onHaptic(BipeHaptic.Tap)
                                model.choose(action, row.id, onCopy)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(painterResource(actionIcon(action).drawable), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            if (paused) stringResource(R.string.bipe_pause_countdown, pauseLeft.toString()) else stringResource(actionTitle(action, row.message)),
                            style = MaterialTheme.typography.bodyLarge,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}

/** Spec message-reactions notes: the quick reactions, in this order. */
internal val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

internal fun actionIcon(action: MessageAction): PhosphorIcon = when (action) {
    MessageAction.REPLY -> PhosphorIcon.ARROW_BEND_UP_LEFT
    MessageAction.EDIT -> PhosphorIcon.PENCIL_SIMPLE
    MessageAction.COPY -> PhosphorIcon.COPY
    MessageAction.FORWARD -> PhosphorIcon.PAPER_PLANE_RIGHT
    MessageAction.SELECT -> PhosphorIcon.CHECK_CIRCLE
    MessageAction.SAVE_STICKER -> PhosphorIcon.STICKER
    MessageAction.KEEP_AUDIO -> PhosphorIcon.DOWNLOAD_SIMPLE
    MessageAction.UNKEEP_AUDIO -> PhosphorIcon.X
    MessageAction.RETRY -> PhosphorIcon.ARROW_CLOCKWISE
    MessageAction.DELETE -> PhosphorIcon.TRASH
    MessageAction.REPORT -> PhosphorIcon.FLAG
}

/** [message] lets retry say Enviar mesmo assim for a message held for an identity change (spec identity-change). */
internal fun actionTitle(action: MessageAction, message: TimelineMessage? = null): Int = when (action) {
    MessageAction.RETRY -> if (message?.sendFailure != null) R.string.bipe_identity_change_send_anyway else R.string.bipe_app_retry
    MessageAction.REPLY -> R.string.bipe_app_message_action_reply
    MessageAction.EDIT -> R.string.bipe_app_message_action_edit
    MessageAction.COPY -> R.string.bipe_app_copy
    MessageAction.FORWARD -> R.string.bipe_forward_action
    MessageAction.SELECT -> R.string.bipe_select_action
    MessageAction.SAVE_STICKER -> R.string.bipe_action_save_sticker
    MessageAction.KEEP_AUDIO -> R.string.bipe_voice_keep
    MessageAction.UNKEEP_AUDIO -> R.string.bipe_voice_unkeep
    MessageAction.DELETE -> R.string.bipe_app_message_action_delete
    MessageAction.REPORT -> R.string.bipe_app_message_action_report
}

/** Spec message-reactions openPicker: the system emoji keyboard. The first emoji typed is the reaction. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmojiPickerSheet(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = BipeTheme.colors
    val focus = remember { FocusRequester() }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceRaised) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp).testTag(BipeScreen.MESSAGE_REACTIONS.id),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bipe_app_message_action_more_reactions), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            BasicTextField(
                value = text,
                onValueChange = { value ->
                    val first = firstGrapheme(value)
                    if (first != null) {
                        text = ""
                        onPick(first)
                    } else {
                        text = value
                    }
                },
                singleLine = true,
                textStyle = TextStyle(fontSize = 32.sp, textAlign = TextAlign.Center, color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .background(colors.surfaceSunken, RoundedCornerShape(12.dp))
                    .padding(8.dp),
            )
        }
    }
}

private fun firstGrapheme(text: String): String? {
    if (text.isBlank()) return null
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.next()
    return if (end == BreakIterator.DONE) null else text.substring(0, end)
}

/** Spec message-reactions openSenders: who reacted with one key, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionSendersSheet(reaction: MessageReaction, onDismiss: () -> Unit) {
    val colors = BipeTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag(BipeScreen.MESSAGE_REACTIONS.id)) {
            Text(stringResource(R.string.bipe_app_reactions_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(reaction.senders, key = { it.userId }) { sender ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(reactionKeyText(reaction.key), style = MaterialTheme.typography.bodyLarge)
                        Text(sender.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                        Text(format.format(sender.reactedAt), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
                    }
                }
            }
        }
    }
}

/** Spec read-receipts openReaders: the read-by sheet of a group message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadersSheet(readers: List<ReceiptReader>, media: MediaLoader, onDismiss: () -> Unit) {
    val colors = BipeTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceRaised) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag(BipeScreen.READ_RECEIPTS.id)) {
            Text(stringResource(R.string.bipe_app_receipt_read_by_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                items(readers, key = { it.userId }) { reader ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Avatar(reader.name, reader.avatarUrl, media, size = 32.dp, userId = reader.userId)
                        Text(reader.name, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                        reader.readAt?.let { Text(format.format(it), style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary) }
                    }
                }
            }
        }
    }
}

/** Spec message-report: an optional reason and the block option. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageReportSheet(
    report: MessageReport,
    onReason: (String) -> Unit,
    onAlsoBlock: (Boolean) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = BipeTheme.colors
    ModalBottomSheet(onDismissRequest = onCancel, containerColor = colors.surfaceRaised) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag(BipeScreen.MESSAGE_REPORT.id),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.bipe_app_message_action_report), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            val placeholder = stringResource(R.string.bipe_report_message_reason)
            BasicTextField(
                value = report.reason,
                onValueChange = onReason,
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
                cursorBrush = SolidColor(colors.accentFill),
                modifier = Modifier.fillMaxWidth().background(colors.surfaceSunken, RoundedCornerShape(12.dp)).padding(12.dp),
                decorationBox = { inner ->
                    if (report.reason.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                    }
                    inner()
                },
            )
            Text(stringResource(R.string.bipe_report_message_body), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bipe_action_block_person), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = report.alsoBlock,
                    onCheckedChange = onAlsoBlock,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accentFill, checkedThumbColor = colors.inkOnAccent),
                )
            }
            if (report.error) {
                Text(stringResource(R.string.bipe_app_report_error), style = MaterialTheme.typography.bodyMedium, color = colors.trustDangerInk)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.bipe_app_cancel), color = colors.inkSecondary) }
                if (report.isSending) {
                    CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(start = 8.dp))
                } else {
                    TextButton(onClick = onSend) { Text(stringResource(R.string.bipe_app_report_send), color = colors.accentInk) }
                }
            }
        }
    }
}
