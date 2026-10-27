// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.R

/** Spec chat-list favourites: large avatars, three per line, the name under each. */
@Composable
fun FavouritesGrid(
    chats: List<ChatSummary>, media: MediaLoader, ownerId: String, commands: (ChatSummary) -> ChatRowCommands,
    onOpen: (String) -> Unit,
) {
    val label = stringResource(R.string.bipe_chat_favourites)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        chats.chunked(COLUMNS).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                line.forEach { chat ->
                    FavouriteCell(chat, media, ownerId, commands(chat), Modifier.weight(1f)) { onOpen(chat.roomId) }
                }
                repeat(COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FavouriteCell(chat: ChatSummary, media: MediaLoader, ownerId: String, commands: ChatRowCommands, modifier: Modifier, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    val menu = rememberChatRowMenu(ownerId, chat.roomId)
    val unread = stringResource(R.string.bipe_chat_unread_state)
    val cellLabel = if (chat.isUnread) "${chat.name}, $unread" else chat.name
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { menu.value = true }, onLongClickLabel = stringResource(R.string.bipe_chat_actions))
                .chatRowSemantics(chat, commands, menu)
                .semantics(mergeDescendants = true) { contentDescription = cellLabel }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                Avatar(chat.name, chat.avatarUrl, media, size = AVATAR.dp, userId = chat.peerUserId)
                if (chat.isUnread) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(14.dp)
                            .border(2.dp, colors.surfaceBase, CircleShape)
                            .padding(2.dp)
                            .background(colors.accentFill, CircleShape),
                    )
                }
            }
            Text(
                chat.name,
                style = MaterialTheme.typography.labelMedium,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        ChatRowMenu(chat, commands, menu)
    }
}

private const val COLUMNS = 3
private const val AVATAR = 72
