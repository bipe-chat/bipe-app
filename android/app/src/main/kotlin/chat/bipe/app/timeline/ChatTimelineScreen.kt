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
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec chat-timeline. */
@Composable
fun ChatTimelineScreen(session: AccountSession, media: MediaLoader, roomId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = remember(roomId) { ChatTimelineModel(session, roomId, scope, InviterGreetings(context, session.userId)) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val colors = BipeTheme.colors
    val roomName by model.roomName.collectAsState()
    val messages by model.messages.collectAsState()
    val isLoadingHistory by model.isLoadingHistory.collectAsState()
    val reachedStart by model.reachedStart.collectAsState()
    val draft by model.draft.collectAsState()
    val inviterHint by model.inviterHint.collectAsState()
    val greeting = stringResource(R.string.bipe_inviter_greeting)
    val listState = rememberLazyListState()

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
                )
            }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(newestFirst, key = { it.id }) { message ->
                    MessageRow(message, onRetry = { model.retrySend(message.id) })
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
            Composer(draft, canSend = draft.isNotBlank(), onEdit = model::editDraft, onSend = model::send)
        }
    }
}

@Composable
private fun MessageRow(message: TimelineMessage, onRetry: () -> Unit) {
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
        TimelineMessage.Kind.UNDECRYPTABLE -> Bubble(message.isOwn) {
            if (!message.isOwn) {
                Text(message.senderName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.inkSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(PhosphorIcon.LOCK_SIMPLE.drawable), contentDescription = null, tint = colors.glyphSecondary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                Text(undecryptableText(message), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = colors.inkSecondary)
            }
        }
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
private fun Bubble(isOwn: Boolean, content: @Composable () -> Unit) {
    val colors = BipeTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start) {
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
private fun Composer(draft: String, canSend: Boolean, onEdit: (String) -> Unit, onSend: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceRaised).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
