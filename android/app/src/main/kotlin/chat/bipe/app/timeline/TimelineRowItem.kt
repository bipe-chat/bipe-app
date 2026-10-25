// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableStateOf
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
import chat.bipe.app.common.bipeMotion
import chat.bipe.app.common.bipeTapTarget
import chat.bipe.app.media.MediaMessage
import chat.bipe.app.media.TimelineMediaRow
import chat.bipe.app.media.VoiceMessage
import chat.bipe.app.media.VoiceMessageRow
import chat.bipe.app.media.VoicePlayer
import chat.bipe.app.polls.PollCard
import chat.bipe.shared.bipeHaptic
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BadgePayload
import chat.bipe.shared.R
import chat.bipe.shared.line
import androidx.compose.ui.platform.LocalContext
import chat.bipe.shared.ScamWarningKind
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.BipeScreen
import androidx.compose.ui.platform.testTag
import chat.bipe.shared.generated.BipeHaptic
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import kotlin.math.roundToInt
import androidx.compose.runtime.collectAsState
import chat.bipe.shared.company.BipeCompanySeals
import chat.bipe.shared.company.BipeCompanySealState
import chat.bipe.app.polls.QuestionCard

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
    isPrivateRoom: Boolean = true,
    voiceState: VoicePlayer.State = VoicePlayer.State(),
    onTogglePlay: (String, VoiceMessage) -> Unit = { _, _ -> },
    onSeek: (String, Float) -> Unit = { _, _ -> },
    /** Action open of spec timeline-media: the row id for media-viewer. */
    onOpenViewer: (String) -> Unit = {},
    /** The account's company seals, for the question card (spec question-card). null: no card, the poll card shows. */
    seals: BipeCompanySeals? = null,
    /** The verified institution badge of the sender (bipe#245): a text message shows as a notice card. */
    institution: BadgePayload? = null,
) {
    val colors = BipeTheme.colors
    when (row.kind) {
        TimelineRow.Kind.MESSAGE -> {
            val message = row.message ?: return
            val details = row.details ?: return
            if (institution != null && !message.isOwn && message.media == null && message.voice == null) {
                var menu by remember(model, row.id) { mutableStateOf(false) }
                val menuLabel = stringResource(R.string.bipe_message_actions)
                Column(Modifier.semantics { customActions = listOf(CustomAccessibilityAction(menuLabel) { menu = true; true }) }) {
                    NoticeCard(message, institution, menuLabel, onLongPress = { menu = true })
                    MessageContextMenu(model, row.id, menu, onDismiss = { menu = false })
                }
                return
            }
            MessageBubbleRow(row, message, details, isDm, isHighlighted, model, media, onLongPress) {
                val mediaMessage = message.media
                val voice = message.voice
                when {
                    mediaMessage != null -> TimelineMediaRow(
                        message = message,
                        media = mediaMessage,
                        loader = media,
                        isPrivateRoom = isPrivateRoom,
                        onOpenViewer = { onOpenViewer(row.id) },
                        onCancelUpload = { model.cancelUpload(row.id) },
                    )
                    voice != null -> VoiceMessageRow(
                        message = message,
                        voice = voice,
                        state = voiceState,
                        onTogglePlay = { onTogglePlay(message.id, voice) },
                        onSeek = { onSeek(message.id, it) },
                    )
                    else -> Text(message.body.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                }
            }
        }
        TimelineRow.Kind.UNDECRYPTABLE -> row.message?.let { message ->
            var menu by remember(model, row.id) { mutableStateOf(false) }
            val menuLabel = stringResource(R.string.bipe_message_actions)
            Column(horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start) {
                Box(Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }, onLongClickLabel = menuLabel)
                    .semantics { customActions = listOf(CustomAccessibilityAction(menuLabel) { menu = true; true }) }) {
                    UndecryptableRow(message, onHistoryHelp)
                }
                MessageContextMenu(model, row.id, menu, onDismiss = { menu = false })
            }
        }
        TimelineRow.Kind.DELETED -> row.message?.let { DeletedRow(it) }
        TimelineRow.Kind.POLL -> row.message?.let { message ->
            val poll = message.poll ?: return@let
            // Spec question-card: a company's structured question (bipe#244). Fails closed to the poll card.
            val sealStates = seals?.states?.collectAsState()
            val seal = remember(sealStates?.value, message.senderId) { seals?.seal(message.senderId) }
            if (poll.isQuestionShape && !message.isOwn && seal is BipeCompanySealState.Verified) {
                Bubble(false) {
                    QuestionCard(poll, message.senderName, seal, model)
                    MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
                }
                return@let
            }
            Box(Modifier.clickable { onOpenPoll(poll.eventId) }) {
                Bubble(message.isOwn) {
                    if (!message.isOwn && !isDm) {
                        Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
                    }
                    PollCard(poll)
                    MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
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
        TimelineRow.Kind.HANDLE_CHANGE -> row.handleChange?.let { line ->
            val context = LocalContext.current
            line.change.line(context, line.name)?.let { SystemLine(it, Modifier.testTag(BipeScreen.HANDLE_CHANGE_LINE.id)) }
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
private fun Bubble(
    isOwn: Boolean,
    isHighlighted: Boolean = false,
    modifier: Modifier = Modifier,
    /** A sticker has no bubble (spec timeline-media). */
    isPlain: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = modifier
                .widthIn(max = 300.dp)
                .then(if (isPlain) Modifier else Modifier.background(if (isOwn) colors.bubbleOwn else colors.bubbleOther, shape))
                .then(if (isHighlighted) Modifier.border(2.dp, colors.accentFill, shape) else Modifier)
                .padding(horizontal = if (isPlain) 0.dp else 12.dp, vertical = if (isPlain) 0.dp else 8.dp),
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
    /** Text, or the media and voice content of specs timeline-media and voice-message. */
    content: @Composable () -> Unit,
) {
    val colors = BipeTheme.colors
    val view = LocalView.current
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    var swipe by remember(row.id) { mutableFloatStateOf(0f) }
    // Reduce Motion: the bubble goes back at once after a swipe (bipe#496).
    val shownSwipe by animateFloatAsState(swipe, animationSpec = bipeMotion(spring<Float>()), label = "swipe")
    val replyLabel = stringResource(R.string.bipe_app_message_action_reply)
    val reactLabel = stringResource(R.string.bipe_a11y_react)
    val menuLabel = stringResource(R.string.bipe_message_actions)
    var menu by remember(model, row.id) { mutableStateOf(false) }
    val openMenu = { menu = true; view.bipeHaptic(BipeHaptic.Hold) }
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
                isPlain = message.media?.kind == MediaMessage.Kind.STICKER,
                modifier = Modifier
                    .offset { IntOffset(shownSwipe.roundToInt(), 0) }
                    .combinedClickable(onClick = {}, onLongClick = openMenu, onLongClickLabel = menuLabel)
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
                        onLongClick { openMenu(); true }
                        customActions = buildList {
                            add(CustomAccessibilityAction(menuLabel) { openMenu(); true })
                            if (details.canReply) add(CustomAccessibilityAction(replyLabel) { model.startReply(row.id); true })
                            // React without the long press menu (bipe#499).
                            if (model.showsReactions(row)) add(CustomAccessibilityAction(reactLabel) { model.moreReactions(row.id); true })
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
                content()
                details.scamWarning?.let { kind ->
                    val announcement = stringResource(R.string.bipe_pause_countdown, BipeRegistries.SCAM_MONEY_PAUSE_SECONDS.toString())
                    LaunchedEffect(row.id, kind) {
                        // Screen readers hear the countdown once, at the start (spec scam-warning notes).
                        if (model.startMoneyPause(row.id, kind)) view.announceForAccessibility(announcement)
                    }
                    ScamWarningCard(kind, rememberPauseSecondsLeft(model, row.id))
                }
                MessageFooter(message, details, onFailedTap = onLongPress, modifier = Modifier.align(Alignment.End))
            }
        }
        MessageContextMenu(model, row.id, menu, onDismiss = { menu = false })
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

/** Spec scam-warning: a calm caution card. Title then the kind text, one element for screen readers. */
@Composable
private fun ScamWarningCard(kind: ScamWarningKind, pauseSecondsLeft: Int) {
    val colors = BipeTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .background(colors.trustCautionWash, shape)
            .border(1.dp, colors.trustCautionLine, shape)
            .padding(8.dp)
            .semantics(mergeDescendants = true) {}
            .testTag(BipeScreen.SCAM_WARNING.id),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustCautionInk, modifier = Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.bipe_scam_warning_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.trustCautionInk)
            Text(stringResource(kind.messageRes), style = MaterialTheme.typography.labelMedium, color = colors.inkPrimary)
            if (pauseSecondsLeft > 0) {
                // The number changes once a second, with no animation (reduced motion or not).
                Text(stringResource(R.string.bipe_pause_countdown, pauseSecondsLeft.toString()), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = colors.trustCautionInk, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/** Time, edited label (message-edit) and the tick of an own message (read-receipts). */
@Composable
private fun MessageFooter(message: TimelineMessage, details: MessageDetails, onFailedTap: () -> Unit, modifier: Modifier = Modifier) {
    val colors = BipeTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val style = MaterialTheme.typography.labelSmall
        MessageTimestamp(message.sentAt)
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
                modifier = Modifier.bipeTapTarget().clickable(onClick = onFailedTap),
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
        readers.take(3).forEach { reader -> Avatar(reader.name, reader.avatarUrl, media, size = 16.dp, userId = reader.userId) }
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

/** Original event time on every message, with the full date exposed to TalkBack. */
@Composable
private fun MessageTimestamp(sentAt: Long, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(sentAt, locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(sentAt) }
    val fullDate = remember(sentAt, locale) { DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.SHORT, locale).format(sentAt) }
    Text(time, style = MaterialTheme.typography.labelSmall, color = BipeTheme.colors.inkSecondary,
        modifier = modifier.semantics { contentDescription = fullDate })
}

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
                modifier = Modifier.bipeTapTarget().clickable(onClick = onHistoryHelp),
            )
        }
        MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
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
        MessageTimestamp(message.sentAt, Modifier.align(Alignment.End))
    }
}
