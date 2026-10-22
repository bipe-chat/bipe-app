// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.polls.PollCard
import chat.bipe.shared.bipeHaptic
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import androidx.compose.ui.platform.testTag
import chat.bipe.shared.generated.BipeHaptic
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import kotlin.math.roundToInt

/** One row of spec timeline-rows. */
@Composable
fun TimelineRowItem(
    row: TimelineRow,
    isDm: Boolean,
    isHighlighted: Boolean,
    model: ChatTimelineModel,
    media: MediaLoader,
    onLongPress: () -> Unit,
    onHistoryHelp: () -> Unit = {},
    onOpenPoll: (String) -> Unit = {},
) {
    val colors = BipeTheme.colors
    when (row.kind) {
        TimelineRow.Kind.MESSAGE -> {
            val message = row.message ?: return
            val details = row.details ?: return
            MessageBubbleRow(row, message, details, isDm, isHighlighted, model, media, onLongPress)
        }
        TimelineRow.Kind.UNDECRYPTABLE -> row.message?.let { message ->
            Box(Modifier.pointerInput(row.id) { detectTapGestures(onLongPress = { onLongPress() }) }) {
                UndecryptableRow(message, onHistoryHelp)
            }
        }
        TimelineRow.Kind.DELETED -> row.message?.let { DeletedRow(it) }
        TimelineRow.Kind.POLL -> row.message?.let { message ->
            val poll = message.poll ?: return@let
            Box(Modifier.clickable { onOpenPoll(poll.eventId) }) {
                Bubble(message.isOwn) {
                    if (!message.isOwn && !isDm) {
                        Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
                    }
                    PollCard(poll)
                }
            }
        }
        TimelineRow.Kind.DATE_SEPARATOR -> row.day?.let { day ->
            SystemLine(daySeparatorText(day), Modifier.semantics { heading() })
        }
        TimelineRow.Kind.READ_MARKER -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.accentFill))
            Text(stringResource(R.string.bipe_app_timeline_new_messages), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = colors.accentInk)
            Box(Modifier.weight(1f).height(1.dp).background(colors.accentFill))
        }
        TimelineRow.Kind.NOT_ENCRYPTED -> SystemLine(stringResource(R.string.bipe_app_timeline_not_encrypted))
        TimelineRow.Kind.CHAT_START -> SystemLine(stringResource(R.string.bipe_app_timeline_start))
    }
}

/** Spec timeline-rows notes: today, yesterday, else the system date in the app language, with the year only when it is not this year. */
@Composable
private fun daySeparatorText(day: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = day }
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (sameDay(then, now)) return stringResource(R.string.bipe_app_timeline_today)
    if (sameDay(then, yesterday)) return stringResource(R.string.bipe_app_timeline_yesterday)
    val locale = LocalConfiguration.current.locales[0]
    val skeleton = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "EEEEdMMMM" else "dMMMMyyyy"
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(then.time)
}

/** A quiet centred line: date separators, the chat start and the not-encrypted notice. */
@Composable
private fun SystemLine(text: String, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.background(colors.surfaceSunken, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Bubble(isOwn: Boolean, isHighlighted: Boolean = false, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = modifier
                .widthIn(max = 300.dp)
                .background(if (isOwn) colors.bubbleOwn else colors.bubbleOther, shape)
                .then(if (isHighlighted) Modifier.border(2.dp, colors.accentFill, shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

/**
 * kind message: sender, reply quote, text, time, edited label, receipt, reactions. Long press opens
 * message-actions; a swipe right starts a reply.
 */
@Composable
private fun MessageBubbleRow(
    row: TimelineRow,
    message: TimelineMessage,
    details: MessageDetails,
    isDm: Boolean,
    isHighlighted: Boolean,
    model: ChatTimelineModel,
    media: MediaLoader,
    onLongPress: () -> Unit,
) {
    val colors = BipeTheme.colors
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    var swipe by remember(row.id) { mutableFloatStateOf(0f) }
    val shownSwipe by animateFloatAsState(swipe, label = "swipe")
    val replyLabel = stringResource(R.string.bipe_app_message_action_reply)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            Icon(
                painterResource(PhosphorIcon.ARROW_BEND_UP_LEFT.drawable),
                contentDescription = null,
                tint = colors.glyphSecondary,
                modifier = Modifier.size(20.dp).alpha((shownSwipe / threshold).coerceIn(0f, 1f)),
            )
            Bubble(
                isOwn = message.isOwn,
                isHighlighted = isHighlighted,
                modifier = Modifier
                    .offset { IntOffset(shownSwipe.roundToInt(), 0) }
                    .pointerInput(row.id) { detectTapGestures(onLongPress = { onLongPress() }) }
                    .pointerInput(row.id, details.canReply) {
                        if (!details.canReply) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (swipe >= threshold) model.startReply(row.id)
                                swipe = 0f
                            },
                            onDragCancel = { swipe = 0f },
                        ) { change, amount ->
                            val next = (swipe + amount).coerceIn(0f, threshold * 1.4f)
                            if ((swipe < threshold) != (next < threshold)) {
                                view.bipeHaptic(if (next >= threshold) BipeHaptic.Threshold else BipeHaptic.Release)
                            }
                            swipe = next
                            change.consume()
                        }
                    }
                    .semantics {
                        onLongClick { onLongPress(); true }
                        if (details.canReply) {
                            customActions = listOf(CustomAccessibilityAction(replyLabel) { model.startReply(row.id); true })
                        }
                    },
            ) {
                if (!message.isOwn && !isDm) {
                    Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
                }
                details.reply?.let { reply ->
                    LaunchedEffect(row.id, reply.state) { model.loadReply(row.id) }
                    ReplyQuote(reply, Modifier.clickable { model.openOriginal(reply.eventId) })
                }
                Text(message.body.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                MessageFooter(message, details, onFailedTap = onLongPress, modifier = Modifier.align(Alignment.End))
            }
        }
        if (details.reactions.isNotEmpty()) {
            ReactionPills(
                reactions = details.reactions,
                onToggle = { key ->
                    view.bipeHaptic(BipeHaptic.Tick)
                    model.toggleReaction(row.id, key)
                },
                onLongPress = { key -> model.openSenders(row.id, key) },
            )
        }
        val receipt = details.receipt
        if (receipt != null && !isDm && receipt.readers.isNotEmpty()) {
            ReaderAvatars(receipt.readers, media, onClick = { model.openReaders(row.id) })
        }
    }
}

/** Time, edited label (message-edit) and the tick of an own message (read-receipts). */
@Composable
private fun MessageFooter(message: TimelineMessage, details: MessageDetails, onFailedTap: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(message.sentAt, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(message.sentAt) }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        Text(time, style = style, color = colors.inkSecondary)
        if (details.isEdited) Text(stringResource(R.string.bipe_app_message_edited), style = style, color = colors.inkSecondary)
        val receipt = details.receipt ?: return@Row
        when (receipt.tick) {
            MessageReceipt.Tick.SENDING -> {
                TickIcon(PhosphorIcon.CLOCK, colors.inkSecondary, null)
                Text(stringResource(R.string.bipe_app_timeline_sending), style = style, color = colors.inkSecondary)
            }
            MessageReceipt.Tick.SENT -> {
                val label = stringResource(R.string.bipe_app_receipt_sent)
                TickIcon(PhosphorIcon.CHECK, colors.inkSecondary, label)
                if (receipt.showsLabel) Text(label, style = style, color = colors.inkSecondary, modifier = Modifier.clearAndSetSemantics {})
            }
            MessageReceipt.Tick.READ -> {
                val label = stringResource(R.string.bipe_app_receipt_read)
                TickIcon(PhosphorIcon.CHECKS, colors.accentInk, label)
                if (receipt.showsLabel) Text(label, style = style, color = colors.inkSecondary, modifier = Modifier.clearAndSetSemantics {})
            }
            MessageReceipt.Tick.FAILED -> Row(
                modifier = Modifier.clickable(onClick = onFailedTap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TickIcon(PhosphorIcon.WARNING_CIRCLE, colors.trustDangerInk, null)
                Text(stringResource(R.string.bipe_app_timeline_failed), style = style, color = colors.trustDangerInk)
            }
        }
    }
}

@Composable
private fun TickIcon(icon: PhosphorIcon, tint: androidx.compose.ui.graphics.Color, label: String?) {
    Icon(painterResource(icon.drawable), contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
}

/** Spec read-receipts, group: at most 3 avatars and a count. */
@Composable
private fun ReaderAvatars(readers: List<ReceiptReader>, media: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val label = stringResource(R.string.bipe_app_receipt_read_by, NumberFormat.getInstance().format(readers.size))
    Row(
        modifier = Modifier.clickable(onClick = onClick).clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((-4).dp),
    ) {
        readers.take(3).forEach { reader -> Avatar(reader.name, reader.avatarUrl, media, size = 16.dp) }
        if (readers.size > 3) {
            Text("+${readers.size - 3}", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Spec message-reply: the quote of the original inside the reply. */
@Composable
fun ReplyQuote(reply: MessageReply, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .background(colors.surfaceSunken.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accentFill, RoundedCornerShape(2.dp)))
        Column {
            val style = MaterialTheme.typography.labelMedium
            when (reply.state) {
                MessageReply.State.LOADING -> Text(stringResource(R.string.bipe_app_message_reply_loading), style = style, color = colors.inkSecondary)
                MessageReply.State.UNAVAILABLE -> Text(stringResource(R.string.bipe_app_message_reply_unavailable), style = style, color = colors.inkSecondary)
                MessageReply.State.READY -> {
                    Text(reply.senderName.orEmpty(), style = style, fontWeight = FontWeight.Bold, color = colors.accentInk)
                    Text(reply.preview.orEmpty(), style = style, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Spec message-reactions: the pills under a message. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionPills(reactions: List<MessageReaction>, onToggle: (String) -> Unit, onLongPress: (String) -> Unit) {
    val colors = BipeTheme.colors
    val sendersLabel = stringResource(R.string.bipe_app_reactions_title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
            val count = NumberFormat.getInstance().format(reaction.count)
            val label = stringResource(R.string.bipe_app_reaction_label, reaction.key, count)
            Row(
                modifier = Modifier
                    .background(if (reaction.includesOwn) colors.accentWash else colors.surfaceSunken, CircleShape)
                    .border(1.dp, if (reaction.includesOwn) colors.accentFill else colors.lineSubtle, CircleShape)
                    .alpha(if (reaction.isSending) 0.6f else 1f)
                    .pointerInput(reaction.key) {
                        detectTapGestures(onTap = { onToggle(reaction.key) }, onLongPress = { onLongPress(reaction.key) })
                    }
                    .clearAndSetSemantics {
                        contentDescription = label
                        selected = reaction.includesOwn
                        customActions = listOf(CustomAccessibilityAction(sendersLabel) { onLongPress(reaction.key); true })
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(reactionKeyText(reaction.key), style = MaterialTheme.typography.bodyMedium)
                Text(count, style = MaterialTheme.typography.labelSmall, color = if (reaction.includesOwn) colors.accentInk else colors.inkSecondary)
            }
        }
    }
}

/** Spec message-reactions notes: keys longer than one grapheme show in full up to 16 characters, then cut. */
fun reactionKeyText(key: String): String = if (key.length > 16) key.take(16) + "…" else key

/** Spec message-undecryptable: a quiet line in the sender's bubble position. */
@Composable
private fun UndecryptableRow(message: TimelineMessage, onHistoryHelp: () -> Unit) {
    val colors = BipeTheme.colors
    Bubble(message.isOwn, modifier = Modifier.testTag(BipeScreen.MESSAGE_UNDECRYPTABLE.id)) {
        if (!message.isOwn) {
            Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.inkSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
            Text(undecryptableText(message), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = colors.inkSecondary)
        }
        // Action openHelp: only historyNotOnThisPhone has a way to help.
        if (message.cause == TimelineMessage.UndecryptableCause.HISTORY_NOT_ON_THIS_PHONE) {
            Text(
                stringResource(R.string.bipe_app_utd_help),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.accentInk,
                modifier = Modifier.clickable(onClick = onHistoryHelp),
            )
        }
    }
}

@Composable
private fun undecryptableText(message: TimelineMessage): String = when (message.cause) {
    TimelineMessage.UndecryptableCause.BEFORE_JOINED -> stringResource(R.string.bipe_app_utd_before_joined)
    TimelineMessage.UndecryptableCause.HISTORY_NOT_ON_THIS_PHONE -> stringResource(R.string.bipe_app_utd_history)
    TimelineMessage.UndecryptableCause.NOT_SHARED_WITH_THIS_PHONE -> stringResource(R.string.bipe_app_utd_not_shared)
    TimelineMessage.UndecryptableCause.UNAPPROVED_SENDER_DEVICE -> stringResource(R.string.bipe_app_utd_unapproved_device, message.senderName)
    TimelineMessage.UndecryptableCause.WAITING, null -> stringResource(R.string.bipe_app_timeline_undecryptable)
}

/** Spec timeline-rows kind deleted: no reactions, quote, receipt or actions. */
@Composable
private fun DeletedRow(message: TimelineMessage) {
    val colors = BipeTheme.colors
    Bubble(message.isOwn) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(PhosphorIcon.TRASH.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(14.dp))
            Text(
                stringResource(if (message.isOwn) R.string.bipe_app_message_deleted_own else R.string.bipe_app_message_deleted),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = colors.inkSecondary,
            )
        }
    }
}
