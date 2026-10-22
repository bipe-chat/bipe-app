// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.InviterGreetings
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.media.MediaViewer
import chat.bipe.app.media.TimelineMediaRow
import chat.bipe.app.media.VoiceMessageRow
import chat.bipe.app.media.VoicePlayer
import chat.bipe.app.groups.ClearedChatsStore
import chat.bipe.app.groups.DmDeleteSheet
import chat.bipe.app.polls.PollCard
import chat.bipe.app.polls.PollCreateScreen
import chat.bipe.app.polls.PollDetailScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec chat-timeline. */
@Composable
fun ChatTimelineScreen(
    session: AccountSession,
    media: MediaLoader,
    roomId: String,
    onBack: () -> Unit,
    onOpenGroupDetails: (() -> Unit)? = null,
    onHistoryHelp: () -> Unit = {},
    onDmDeleted: () -> Unit = onBack,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = remember(roomId) { ChatTimelineModel(session, roomId, scope, InviterGreetings(context, session.userId)) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val colors = BipeTheme.colors
    val roomName by model.roomName.collectAsState()
    val allMessages by model.messages.collectAsState()
    val markers by ClearedChatsStore.markers.collectAsState()
    // Spec dm-delete: events at or below the marker of a cleared DM are gone.
    val messages = remember(allMessages, markers) {
        val marker = markers.marker(roomId)
        if (marker == null) allMessages else allMessages.filter { it.sentAt > marker }
    }
    val isLoadingHistory by model.isLoadingHistory.collectAsState()
    val reachedStart by model.reachedStart.collectAsState()
    val draft by model.draft.collectAsState()
    // group-details is for groups only: RoomInfo.isDm is the one DM test (ADR 0141).
    val isGroup by produceState(false, roomId) {
        value = runCatching { session.client.getRoom(roomId)?.roomInfo() }.getOrNull()?.let { !it.isDm && !it.isSpace } ?: false
    }
    val isDm by produceState(false, roomId) {
        value = runCatching { session.client.getRoom(roomId)?.roomInfo() }.getOrNull()?.isDm ?: false
    }
    var dmMenu by remember { mutableStateOf(false) }
    var deletingDm by rememberSaveable { mutableStateOf(false) }
    if (deletingDm) {
        DmDeleteSheet(session, roomId, onDismiss = { deletingDm = false }, onDeleted = {
            deletingDm = false
            onDmDeleted()
        })
    }
    val isPrivateRoom by model.isPrivateRoom.collectAsState()
    val inviterHint by model.inviterHint.collectAsState()
    val greeting = stringResource(R.string.bipe_inviter_greeting)
    val listState = rememberLazyListState()
    val voicePlayer = remember(roomId) { VoicePlayer(context.applicationContext, media, scope) }
    val voiceState by voicePlayer.state.collectAsState()
    DisposableEffect(voicePlayer) { onDispose { voicePlayer.stop() } }
    var viewerRowId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingPoll by rememberSaveable { mutableStateOf(false) }
    var openPollId by rememberSaveable { mutableStateOf<String?>(null) }
    if (creatingPoll) {
        PollCreateScreen(model) { creatingPoll = false }
        return
    }
    openPollId?.let { eventId ->
        PollDetailScreen(session, model, eventId) { openPollId = null }
        return
    }

    // The list is reversed: index 0 is the newest message, so the view opens at the bottom.
    val newestFirst = remember(messages) { messages.asReversed() }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_TIMELINE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(PhosphorIcon.CARET_LEFT.drawable), contentDescription = null, tint = colors.glyphPrimary)
                }
                Avatar(roomName, null, media, size = 32.dp)
                Spacer(Modifier.size(8.dp))
                Text(
                    roomName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isGroup && onOpenGroupDetails != null) {
                    IconButton(onClick = onOpenGroupDetails) {
                        Icon(
                            painterResource(PhosphorIcon.INFO.drawable),
                            contentDescription = stringResource(R.string.bipe_app_group_details_title),
                            tint = colors.glyphPrimary,
                        )
                    }
                }
                if (isDm) {
                    Box {
                        IconButton(onClick = { dmMenu = true }) {
                            Icon(
                                painterResource(PhosphorIcon.INFO.drawable),
                                contentDescription = stringResource(R.string.bipe_dm_details_title),
                                tint = colors.glyphPrimary,
                            )
                        }
                        DropdownMenu(expanded = dmMenu, onDismissRequest = { dmMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bipe_dm_delete_action), color = colors.trustDangerInk) },
                                onClick = {
                                    dmMenu = false
                                    deletingDm = true
                                },
                            )
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(newestFirst, key = { it.id }) { message ->
                    // Media and voice rows live in media/ (specs timeline-media, voice-message).
                    val item = message.media
                    val voice = message.voice
                    when {
                        item != null -> TimelineMediaRow(
                            message = message,
                            media = item,
                            loader = media,
                            isPrivateRoom = isPrivateRoom,
                            onOpenViewer = { viewerRowId = message.id },
                            onCancelUpload = { model.cancelUpload(message.id) },
                            onRetrySend = { model.retrySend(message.id) },
                        )
                        voice != null -> VoiceMessageRow(
                            message = message,
                            voice = voice,
                            state = voiceState,
                            onTogglePlay = { voicePlayer.togglePlay(message.id, voice) },
                            onSeek = { voicePlayer.seek(message.id, it) },
                            onRetrySend = { model.retrySend(message.id) },
                        )
                        else -> MessageRow(message, onRetry = { model.retrySend(message.id) }, onHistoryHelp = onHistoryHelp, onOpenPoll = { openPollId = it })
                    }
                }
                item(key = "history") {
                    // Composed while the top of the list is on screen, again after each page (action loadHistory).
                    LaunchedEffect(messages.size) { model.loadHistory() }
                    val text = when {
                        reachedStart -> stringResource(R.string.bipe_app_timeline_start)
                        isLoadingHistory -> stringResource(R.string.bipe_app_timeline_loading_history)
                        else -> null
                    }
                    if (text != null) {
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.inkSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Spacer(Modifier.size(1.dp))
                    }
                }
            }
            inviterHint?.let { hint -> InviterHintRow(hint, roomName) { model.sendGreeting(greeting) } }
            Composer(draft, canSend = draft.isNotBlank(), onEdit = model::editDraft, onSend = model::send, onCreatePoll = { creatingPoll = true })
        }
    }
    viewerRowId?.let { rowId ->
        MediaViewer(
            messages = messages,
            startRowId = rowId,
            loader = media,
            onLoadHistory = model::loadHistory,
            onClose = { viewerRowId = null },
        )
    }
}

@Composable
private fun MessageRow(message: TimelineMessage, onRetry: () -> Unit, onHistoryHelp: () -> Unit, onOpenPoll: (String) -> Unit) {
    val colors = BipeTheme.colors
    when (message.kind) {
        TimelineMessage.Kind.TEXT -> Bubble(message.isOwn) {
            if (!message.isOwn) {
                Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
            }
            Text(message.body.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            when (message.sendState) {
                TimelineMessage.SendState.SENT -> Unit
                TimelineMessage.SendState.SENDING -> Text(
                    stringResource(R.string.bipe_app_timeline_sending),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.inkSecondary,
                )
                TimelineMessage.SendState.FAILED -> Row(
                    modifier = Modifier.clickable(onClick = onRetry),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(painterResource(PhosphorIcon.WARNING_CIRCLE.drawable), contentDescription = null, tint = colors.trustDangerInk, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.bipe_app_timeline_failed), style = MaterialTheme.typography.labelSmall, color = colors.trustDangerInk)
                }
            }
        }
        TimelineMessage.Kind.UNDECRYPTABLE -> Bubble(message.isOwn, Modifier.testTag(BipeScreen.MESSAGE_UNDECRYPTABLE.id)) {
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
        TimelineMessage.Kind.POLL -> message.poll?.let { poll ->
            Box(Modifier.clickable { onOpenPoll(poll.eventId) }) {
                Bubble(message.isOwn) {
                    if (!message.isOwn) {
                        Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.accentInk)
                    }
                    PollCard(poll)
                }
            }
        }
        // ChatTimelineScreen shows these with TimelineMediaRow and VoiceMessageRow.
        TimelineMessage.Kind.MEDIA, TimelineMessage.Kind.VOICE -> Unit
        // The slice shows text only (spec chat-timeline notes). bipe-shared has no string for other events yet.
        TimelineMessage.Kind.UNSUPPORTED -> Unit
    }
}

/** Spec message-undecryptable: the cause in plain words. */
@Composable
private fun undecryptableText(message: TimelineMessage): String = when (message.cause) {
    TimelineMessage.UndecryptableCause.BEFORE_JOINED -> stringResource(R.string.bipe_app_utd_before_joined)
    TimelineMessage.UndecryptableCause.HISTORY_NOT_ON_THIS_PHONE -> stringResource(R.string.bipe_app_utd_history)
    TimelineMessage.UndecryptableCause.NOT_SHARED_WITH_THIS_PHONE -> stringResource(R.string.bipe_app_utd_not_shared)
    TimelineMessage.UndecryptableCause.UNAPPROVED_SENDER_DEVICE -> stringResource(R.string.bipe_app_utd_unapproved_device, message.senderName)
    TimelineMessage.UndecryptableCause.WAITING, null -> stringResource(R.string.bipe_app_timeline_undecryptable)
}

@Composable
private fun Bubble(isOwn: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = BipeTheme.colors
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(if (isOwn) colors.bubbleOwn else colors.bubbleOther, RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

/** signup-done notes: until the inviter's note arrives, the chat says the inviter knows, with a one-tap greeting. */
@Composable
private fun InviterHintRow(hint: InviterGreetings.Greeting, inviterName: String, greet: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth().background(colors.accentWash).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.bipe_inviter_knows_you_joined, inviterName), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint.handle?.let { stringResource(R.string.bipe_inviter_hint, it) }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.bipe_inviter_hint_action),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.background(colors.accentFill, CircleShape).clickable(onClick = greet).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Composer(draft: String, canSend: Boolean, onEdit: (String) -> Unit, onSend: () -> Unit, onCreatePoll: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Opens poll-create (the attach menu comes with the composer spec, bipe-chat/bipe#563).
        IconButton(onClick = onCreatePoll) {
            Icon(
                painterResource(PhosphorIcon.CHART_BAR.drawable),
                contentDescription = stringResource(R.string.bipe_app_poll_create_title),
                tint = colors.glyphPrimary,
            )
        }
        val placeholder = stringResource(R.string.bipe_app_timeline_composer_placeholder)
        BasicTextField(
            value = draft,
            onValueChange = onEdit,
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.inkPrimary),
            cursorBrush = SolidColor(colors.accentFill),
            modifier = Modifier
                .weight(1f)
                .background(colors.surfaceSunken, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
                    }
                    inner()
                }
            },
        )
        IconButton(
            onClick = onSend,
            enabled = canSend,
            modifier = Modifier.background(if (canSend) colors.accentFill else colors.surfaceSunken, CircleShape),
        ) {
            Icon(
                painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable),
                contentDescription = stringResource(R.string.bipe_app_timeline_send),
                tint = if (canSend) colors.inkOnAccent else colors.inkDisabled,
            )
        }
    }
}
