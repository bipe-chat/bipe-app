// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.timeline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.share.searchFold
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.messageEventContentNew

/** Type ForwardChat of spec message-forward. */
data class ForwardChat(val roomId: String, val name: String, val avatarUrl: String?)

/** Spec message-forward notes: at most this many chats per forward. */
private const val FORWARD_LIMIT = 5

/**
 * Spec message-forward (bipe-chat/bipe#664, bipe-chat/bipe#860): the chat picker for copies of sent messages, in timeline order. [onClose] gets the names of
 * the chats whose send failed, or null after cancel.
 */
@Composable
fun MessageForwardSheet(client: Client, msgTypes: List<MessageType>, media: MediaLoader, onClose: (List<String>?) -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val noName = stringResource(R.string.bipe_dm_no_name)
    var chats by remember { mutableStateOf(emptyList<ForwardChat>()) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptyList<String>()) }
    var sending by remember { mutableStateOf(false) }
    var showsLimit by remember { mutableStateOf(false) }
    // State chats: joined chats, spaces left out, by name.
    LaunchedEffect(client) {
        chats = runCatching { client.rooms() }.getOrDefault(emptyList())
            .filter { runCatching { it.membership() == Membership.JOINED && !it.isSpace() }.getOrDefault(false) }
            // Spec drafts notes: never the drafts room.
            .filter { room -> runCatching { !AssistantDraftsStore.isDraftsRoom(room.id(), client.userId()) }.getOrDefault(true) }
            .map { ForwardChat(it.id(), it.displayName()?.takeIf { name -> name.isNotBlank() } ?: noName, it.avatarUrl()) }
            .sortedBy { searchFold(it.name) }
    }
    val shown = remember(chats, query) {
        val wanted = searchFold(query.trim())
        if (wanted.isEmpty()) chats else chats.filter { searchFold(it.name).contains(wanted) }
    }

    /** Action toggle. */
    fun toggle(roomId: String) {
        picked = when {
            roomId in picked -> picked - roomId
            picked.size >= FORWARD_LIMIT -> picked.also { showsLimit = true }
            else -> picked + roomId
        }
    }

    /** Action send: the copies per picked chat; a failure does not stop the other chats. A failed message stops its chat,
     * so the copies never arrive out of order. */
    fun send() {
        if (picked.isEmpty() || sending) return
        sending = true
        scope.launch {
            val failed = picked.filter { roomId ->
                runCatching {
                    val timeline = client.getRoom(roomId)?.timeline() ?: error("no room")
                    msgTypes.forEach { timeline.send(messageEventContentNew(it)) }
                }.isFailure
            }.map { roomId -> chats.firstOrNull { it.roomId == roomId }?.name ?: roomId }
            sending = false
            onClose(failed)
        }
    }

    Dialog(onDismissRequest = { onClose(null) }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose(null) }
        Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.MESSAGE_FORWARD.id), color = colors.surfaceBase) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onClose(null) }, enabled = !sending) { Text(stringResource(R.string.bipe_app_cancel), color = colors.accentInk) }
                    Text(
                        stringResource(R.string.bipe_forward_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.inkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (sending) {
                        CircularProgressIndicator(color = colors.accentFill, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 16.dp).size(20.dp))
                    } else {
                        TextButton(onClick = ::send, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.bipe_forward_action)) }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.bipe_app_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(shown, key = { it.roomId }) { chat ->
                        val isPicked = chat.roomId in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !sending) { toggle(chat.roomId) }
                                .semantics { selected = isPicked }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Avatar(chat.name, chat.avatarUrl, media, size = 40.dp)
                            Text(
                                chat.name,
                                color = colors.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            // The picked chat: a check mark, not only a colour.
                            if (isPicked) {
                                Icon(painterResource(PhosphorIcon.CHECK.drawable), contentDescription = null, tint = colors.accentInk, modifier = Modifier.size(20.dp))
                            } else {
                                Spacer(Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showsLimit) {
        AlertDialog(
            onDismissRequest = { showsLimit = false },
            text = { Text(stringResource(R.string.bipe_forward_limit, FORWARD_LIMIT.toString())) },
            confirmButton = { TextButton(onClick = { showsLimit = false }) { Text(stringResource(R.string.bipe_action_continue)) } },
        )
    }
}
