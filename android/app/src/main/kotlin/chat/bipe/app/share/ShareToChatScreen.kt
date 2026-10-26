// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatRow
import chat.bipe.app.chatlist.ChatSummary
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.media.upload.UploadPreviewScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.messageEventContentNew
import java.text.Normalizer

/**
 * Spec share-to-chat: the chat picker for the current account, with search, recent chats first and an optional
 * message. With files, a tap on a chat opens upload-preview, whose Send sends. With text only, Send sends the message.
 * Nothing leaves the phone before that Send. [onDone] gets the chat that got the share, or null after cancel.
 */
@Composable
fun ShareToChatScreen(share: PendingShare, chatList: ChatListModel, media: MediaLoader, onDone: (String?) -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val chats by chatList.chats.collectAsState()
    var query by remember(share.id) { mutableStateOf("") }
    var message by remember(share.id) { mutableStateOf(share.text) }
    var picked by remember(share.id) { mutableStateOf<ChatSummary?>(null) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<Pair<String, Timeline>?>(null) }
    val hasFiles = share.files.isNotEmpty()
    BackHandler { onDone(null) }

    // The room list order is newest activity first.
    val shown = remember(chats, query) {
        val wanted = fold(query.trim())
        if (wanted.isEmpty()) chats else chats.filter { fold(it.name).contains(wanted) }
    }

    /** Action pick: with files, upload-preview for that chat; else the chat is selected for Send. */
    fun pick(chat: ChatSummary) {
        if (!hasFiles) {
            picked = chat
            return
        }
        sending = true
        scope.launch {
            val timeline = runCatching { chatList.session.client.getRoom(chat.roomId)?.timeline() }.getOrNull()
            if (timeline == null) failed = true else upload = chat.roomId to timeline
            sending = false
        }
    }

    // A sharing shortcut picked the chat already.
    LaunchedEffect(share.id, chats) {
        val target = share.shortcut ?: return@LaunchedEffect
        if (picked != null || upload != null || target.accountUserId != chatList.session.userId) return@LaunchedEffect
        chats.firstOrNull { it.roomId == target.roomId }?.let(::pick)
    }

    val uploadTarget = upload
    if (uploadTarget != null) {
        UploadPreviewScreen(
            share.files, chatList.session.client, uploadTarget.second, null,
            onClose = { upload = null },
            caption = message,
            onSent = { onDone(uploadTarget.first) },
        )
    }

    /** Action send: text only, one text message to the picked chat. */
    fun send() {
        val chat = picked ?: return
        val body = message.trim()
        if (body.isEmpty() || sending) return
        sending = true
        scope.launch {
            val sent = runCatching {
                val timeline = chatList.session.client.getRoom(chat.roomId)?.timeline() ?: error("no room")
                timeline.send(messageEventContentNew(MessageType.Text(TextMessageContent(body, null))))
            }.isSuccess
            sending = false
            if (sent) onDone(chat.roomId) else failed = true
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.SHARE_TO_CHAT.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk) }
                Text(stringResource(R.string.bipe_share_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.bipe_app_search_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (share.droppedOverLimit) {
                Text(
                    stringResource(R.string.bipe_share_too_many),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) stringResource(R.string.bipe_app_chat_list_empty)
                            else stringResource(R.string.bipe_app_search_no_results, query.trim()),
                            color = colors.inkSecondary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(shown, key = { it.roomId }) { chat ->
                    val isPicked = picked?.roomId == chat.roomId
                    Row(modifier = Modifier.semantics { selected = isPicked }, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            ChatRow(chat, media) { if (!sending) pick(chat) }
                        }
                        // The picked chat: a check mark, not only a colour.
                        if (isPicked) {
                            Icon(
                                painterResource(PhosphorIcon.CHECK.drawable),
                                contentDescription = null,
                                tint = colors.accentInk,
                                modifier = Modifier.padding(end = 16.dp).size(20.dp),
                            )
                        }
                    }
                }
            }
            if (!hasFiles) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text(stringResource(R.string.bipe_share_message_placeholder)) },
                        maxLines = 6,
                        enabled = !sending,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val canSend = picked != null && message.isNotBlank() && !sending
                    IconButton(
                        onClick = ::send,
                        enabled = canSend,
                        modifier = Modifier.background(if (canSend) colors.accentFill else colors.surfaceSunken, CircleShape),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(color = colors.inkOnAccent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                painterResource(PhosphorIcon.PAPER_PLANE_RIGHT.drawable),
                                contentDescription = stringResource(R.string.bipe_app_timeline_send),
                                tint = if (canSend) colors.inkOnAccent else colors.inkDisabled,
                            )
                        }
                    }
                }
            }
        }
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            text = { Text(stringResource(R.string.bipe_error_generic)) },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.bipe_app_close)) } },
            containerColor = colors.surfaceRaised,
            textContentColor = colors.inkPrimary,
        )
    }
}

/** Search ignores case and accents. */
private fun fold(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
