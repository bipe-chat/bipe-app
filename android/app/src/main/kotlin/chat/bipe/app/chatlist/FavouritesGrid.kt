// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.bipeHaptic
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic

/**
 * Spec chat-list favourites: large avatars, three per line, the name under each.
 * In reorder mode (spec chat-list reorderingFavourites) a cell drags onto another cell to take its place; tap does nothing.
 */
@Composable
fun FavouritesGrid(
    chats: List<ChatSummary>, media: MediaLoader, ownerId: String, commands: (ChatSummary) -> ChatRowCommands,
    onOpen: (String) -> Unit,
    reordering: Boolean = false,
    onDone: () -> Unit = {},
    onMove: (roomId: String, index: Int) -> Unit = { _, _ -> },
) {
    val label = stringResource(R.string.bipe_chat_favourites)
    val colors = BipeTheme.colors
    val view = LocalView.current
    // Drag state of reorder mode: cell bounds in root coordinates, the dragged cell, its offset and the cell under it.
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<String?>(null) }
    val currentChats by rememberUpdatedState(chats)
    val move by rememberUpdatedState(onMove)
    fun endDrag() {
        val from = dragging
        val to = target?.let { id -> currentChats.indexOfFirst { it.roomId == id } } ?: -1
        if (from != null && to >= 0 && target != from) { view.bipeHaptic(BipeHaptic.Hold); move(from, to) }
        dragging = null
        target = null
        offset = Offset.Zero
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (reordering) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bipe_chat_favourites_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.inkSecondary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.bipe_chat_favourites_reorder_done), color = colors.accentFill, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        chats.chunked(COLUMNS).forEachIndexed { lineIndex, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.zIndex(if (line.any { it.roomId == dragging }) 1f else 0f)) {
                line.forEachIndexed { column, chat ->
                    val index = lineIndex * COLUMNS + column
                    val cell = Modifier.weight(1f)
                    if (reordering) {
                        val earlier = stringResource(R.string.bipe_chat_favourite_move_earlier)
                        val later = stringResource(R.string.bipe_chat_favourite_move_later)
                        FavouriteCell(
                            chat, media, ownerId, commands(chat),
                            cell.onGloballyPositioned { bounds[chat.roomId] = it.boundsInRoot() }
                                .zIndex(if (dragging == chat.roomId) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging == chat.roomId) { translationX = offset.x; translationY = offset.y }
                                    alpha = if (target == chat.roomId) 0.5f else 1f
                                }
                                .pointerInput(chat.roomId) {
                                    detectDragGestures(
                                        onDragStart = { dragging = chat.roomId; offset = Offset.Zero },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            offset += amount
                                            val centre = bounds[chat.roomId]?.center?.plus(offset)
                                            target = centre?.let { point -> bounds.entries.firstOrNull { it.key != chat.roomId && it.value.contains(point) }?.key }
                                        },
                                        onDragEnd = ::endDrag,
                                        onDragCancel = { dragging = null; target = null; offset = Offset.Zero },
                                    )
                                }
                                .semantics {
                                    customActions = buildList {
                                        if (index > 0) add(CustomAccessibilityAction(earlier) { onMove(chat.roomId, index - 1); true })
                                        if (index < chats.size - 1) add(CustomAccessibilityAction(later) { onMove(chat.roomId, index + 1); true })
                                    }
                                },
                            reordering = true,
                        ) {}
                    } else {
                        FavouriteCell(chat, media, ownerId, commands(chat), cell) { onOpen(chat.roomId) }
                    }
                }
                repeat(COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FavouriteCell(
    chat: ChatSummary, media: MediaLoader, ownerId: String, commands: ChatRowCommands, modifier: Modifier,
    reordering: Boolean = false, onOpen: () -> Unit,
) {
    val colors = BipeTheme.colors
    val menu = rememberChatRowMenu(ownerId, chat.roomId)
    val view = LocalView.current
    val unread = stringResource(R.string.bipe_chat_unread_state)
    val cellLabel = if (chat.isUnread) "${chat.name}, $unread" else chat.name
    Box(modifier) {
        Column(
            (if (reordering) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = { view.bipeHaptic(BipeHaptic.Hold); menu.value = true }, onLongClickLabel = stringResource(R.string.bipe_chat_actions))
                .chatRowSemantics(chat, commands, menu))
                .semantics(mergeDescendants = true) { contentDescription = cellLabel }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The avatar is 80% of its column, so it follows the screen width, not the font scale.
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val avatar = minOf(maxWidth * 0.8f, AVATAR_MAX.dp)
                Avatar(chat.name, chat.avatarUrl, media, size = avatar, userId = chat.peerUserId)
                if (chat.isUnread) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(end = (maxWidth - avatar) / 2).size(20.dp)
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
        if (!reordering) ChatRowMenu(chat, commands, menu)
    }
}

private const val COLUMNS = 3
private const val AVATAR_MAX = 92
