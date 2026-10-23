// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.pixcheck

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.chatlist.ChatListModel
import chat.bipe.app.chatlist.ChatRow
import chat.bipe.app.chatlist.ChatSummary
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.media.upload.UploadPreviewScreen
import chat.bipe.shared.R
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.messageEventContentNew

/**
 * Spec pix-check-result, action sendToChat in the app: the editable preview, the chat choice for the current
 * account, then a final confirmation. Nothing leaves the phone before Send; Cancel deletes the preview.
 */
@Composable
fun PixSendScreen(preview: PixPreview, chatList: ChatListModel, media: MediaLoader, onDone: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val chats by chatList.chats.collectAsState()
    var text by remember(preview.id) { mutableStateOf(preview.text.orEmpty()) }
    var chosen by remember { mutableStateOf<ChatSummary?>(null) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<Timeline?>(null) }
    val image = preview.image
    BackHandler(onBack = onDone)

    val uploadTimeline = upload
    if (uploadTimeline != null && image != null) {
        UploadPreviewScreen(listOf(image to "image/*"), chatList.session.client, uploadTimeline, null, onClose = onDone)
        return
    }

    /** The final Send after the chat choice. A text goes as typed; an image goes through upload-preview. */
    fun send(chat: ChatSummary) {
        sending = true
        scope.launch {
            val timeline = runCatching { chatList.session.client.getRoom(chat.roomId)?.timeline() }.getOrNull()
            if (timeline == null) {
                failed = true
            } else if (image != null) {
                upload = timeline
            } else {
                val body = text.trim()
                val sent = body.isNotEmpty() && runCatching {
                    timeline.send(messageEventContentNew(MessageType.Text(TextMessageContent(body, null))))
                }.isSuccess
                if (sent) onDone() else failed = true
            }
            sending = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk) }
                Text(stringResource(R.string.bipe_pix_check_send_to_chat), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            }
            // The account that sends: shown before any choice.
            Text(chatList.session.userId, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary, modifier = Modifier.padding(horizontal = 16.dp))
            if (image != null) {
                val bitmap = remember(image) { BitmapFactory.decodeFile(image.path)?.asImageBitmap() }
                bitmap?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(16.dp))
                }
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(16.dp),
                    enabled = !sending,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(chats, key = { it.roomId }) { chat ->
                    ChatRow(chat, media) { if (!sending) chosen = chat }
                }
            }
        }
    }
    chosen?.let { chat ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(chat.name) },
            confirmButton = {
                TextButton(onClick = {
                    chosen = null
                    send(chat)
                }) { Text(stringResource(R.string.bipe_app_timeline_send), color = colors.accentInk) }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
            containerColor = colors.surfaceRaised,
            titleContentColor = colors.inkPrimary,
        )
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
