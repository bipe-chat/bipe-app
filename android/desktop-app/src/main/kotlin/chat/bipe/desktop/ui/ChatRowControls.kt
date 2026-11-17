// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.desktop.chat.ChatRow

internal sealed interface WorkspacePopup {
    data object Accounts : WorkspacePopup
    data class Room(val id: String) : WorkspacePopup
    data class Notifications(val id: String) : WorkspacePopup
}
internal sealed interface FavouriteEditing {
    data object Normal : FavouriteEditing
    data object Reordering : FavouriteEditing
    data class Dragging(val room: String, val shown: List<String>, val origin: Rect,
                        val offset: Offset = Offset.Zero, val target: String? = null) : FavouriteEditing
}
internal data class ChatRowAction(val label: String, val enabled: Boolean, val run: () -> Unit)

/** The same commands are available to mouse, keyboard, touch and accessibility; no permanent overflow button. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable internal fun ChatRowSurface(selected: Boolean, colors: DesktopColors, onOpen: () -> Unit,
                                       modifier: Modifier, menuOpen: Boolean, showMenu: (Boolean) -> Unit,
                                       actions: List<ChatRowAction>, s: DesktopCatalog, content: @Composable () -> Unit) {
    val menuLabel = s.text("bipe_chat_actions")
    Box(modifier) {
        Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).semantics {
            this.selected = selected
            customActions = buildList {
                add(CustomAccessibilityAction(menuLabel) { showMenu(true); true })
                actions.filter { it.enabled }.forEach { action -> add(CustomAccessibilityAction(action.label) { action.run(); true }) }
            }
        }.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && ((event.key == Key.F10 && event.isShiftPressed) || event.key == Key.Menu)) {
                showMenu(true); true
            } else false
        }.onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) showMenu(true)
        }.combinedClickable(onClick = onOpen, onLongClick = { showMenu(true) }, onLongClickLabel = menuLabel, role = Role.Button),
            color = if (selected) colors["accent.fill"] else colors["surface.base"],
            contentColor = if (selected) colors["ink.onAccent"] else colors["ink.primary"], content = content)
        DropdownMenu(expanded = menuOpen, onDismissRequest = { showMenu(false) }) {
            actions.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, enabled = action.enabled,
                onClick = { showMenu(false); action.run() }) }
        }
    }
}

/** A lazy grid line shares a single drag state with the other lines, so a drop can cross a line boundary. */
@Composable internal fun FavouriteGridLine(line: List<ChatRow>, shown: List<String>, selected: String?,
                                         runtime: MatrixAccountRuntime, colors: DesktopColors, s: DesktopCatalog,
                                         editing: FavouriteEditing, edit: (FavouriteEditing) -> Unit,
                                         bounds: MutableMap<String, Rect>, enabled: Boolean,
                                         popup: WorkspacePopup?, show: (WorkspacePopup?) -> Unit,
                                         actions: (ChatRow) -> List<ChatRowAction>, open: (String) -> Unit,
                                         move: (String, Int, List<String>) -> Unit) {
    val currentEditing by rememberUpdatedState(editing)
    val currentShown by rememberUpdatedState(shown)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentMove by rememberUpdatedState(move)
    val reordering = editing != FavouriteEditing.Normal
    val drag = editing as? FavouriteEditing.Dragging
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        .zIndex(if (line.any { it.id == drag?.room }) 1f else 0f)
        .semantics { contentDescription = s.text("bipe_chat_favourites") }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        line.forEach { row -> key(row.id) {
            DisposableEffect(row.id) { onDispose { bounds.remove(row.id) } }
            var cell = Modifier.weight(1f).onGloballyPositioned { bounds[row.id] = it.boundsInRoot() }
            if (reordering) cell = cell.zIndex(if (drag?.room == row.id) 1f else 0f).graphicsLayer {
                if (drag?.room == row.id) { translationX = drag.offset.x; translationY = drag.offset.y }
                alpha = if (drag?.target == row.id) 0.5f else 1f
            }.pointerInput(row.id, enabled) {
                detectDragGestures(onDragStart = {
                    if (currentEnabled) bounds[row.id]?.let { edit(FavouriteEditing.Dragging(row.id, currentShown.toList(), it)) }
                }, onDrag = { change, amount ->
                    val active = currentEditing as? FavouriteEditing.Dragging
                    if (active?.room == row.id) {
                        change.consume()
                        val offset = active.offset + amount
                        val point = active.origin.center + offset
                        val target = bounds.entries.firstOrNull { it.key != row.id && it.value.contains(point) }?.key
                        edit(active.copy(offset = offset, target = target))
                    }
                }, onDragCancel = { edit(FavouriteEditing.Reordering) }, onDragEnd = {
                    val active = currentEditing as? FavouriteEditing.Dragging
                    edit(FavouriteEditing.Reordering)
                    if (currentEnabled && active?.room == row.id && active.shown == currentShown) {
                        val index = active.target?.let(active.shown::indexOf) ?: -1
                        if (index >= 0) currentMove(row.id, index, active.shown)
                    }
                })
            }
            val unread = s.text("bipe_chat_unread_state")
            ChatRowSurface(row.id == selected && !reordering, colors, { if (!reordering) open(row.id) },
                cell.semantics(mergeDescendants = true) { contentDescription = if (row.hasUnread) "${row.name}, $unread" else row.name },
                popup == WorkspacePopup.Room(row.id), { show(if (it) WorkspacePopup.Room(row.id) else null) }, actions(row), s) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val size = minOf(maxWidth * 0.8f, 92.dp)
                        Box(Modifier.size(size)) {
                            Avatar(row, runtime.media, runtime.identity, colors, size)
                            if (row.hasUnread) Box(Modifier.align(Alignment.TopEnd).size(20.dp)
                                .border(2.dp, colors["surface.base"], CircleShape).padding(2.dp).background(colors["accent.fill"], CircleShape))
                        }
                    }
                    Text(row.name, Modifier.padding(horizontal = 4.dp, vertical = 6.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        } }
        repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable internal fun ArchiveScopeRow(title: String, back: Boolean, colors: DesktopColors, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = open)
        .semantics(mergeDescendants = true) {}.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(sharedIcon(if (back) "caret-left" else "archive"), contentDescription = null,
                tint = colors["ink.primary"], modifier = Modifier.size(24.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
