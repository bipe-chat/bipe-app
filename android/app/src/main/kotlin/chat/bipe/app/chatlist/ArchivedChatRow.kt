// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.MediaLoader
import chat.bipe.shared.R

/** The account-owned commands of one chat row (spec chat-row-actions). The long-press menu and the favourites grid share them. */
class ChatRowCommands(
    val archived: Boolean,
    val readPending: Boolean,
    val onArchive: () -> Unit,
    val onRead: () -> Unit,
    val onNotifications: () -> Unit,
    val onInfo: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    /** Favourite or unfavourite. null for an archived chat: the SDK drops m.lowpriority when it sets m.favourite. */
    val onFavourite: (() -> Unit)?,
)

/** The labels of [ChatRowCommands] for [chat], in menu order. */
private class ChatRowLabels(chat: ChatSummary, commands: ChatRowCommands, val strings: (Int) -> String) {
    val archive = strings(if (commands.archived) R.string.bipe_unarchive_action else R.string.bipe_archive_action)
    val favourite = strings(if (chat.isFavourite) R.string.bipe_chat_unfavourite_action else R.string.bipe_chat_favourite_action)
    val read = strings(if (chat.isUnread) R.string.bipe_app_notification_mark_read else R.string.bipe_chat_mark_unread)
    val notifications = strings(R.string.bipe_settings_notifications)
    val details = strings(R.string.bipe_chat_details)
    val menu = strings(R.string.bipe_chat_actions)
    val delete = strings(R.string.bipe_dm_delete_action)
}

@Composable
private fun rowLabels(chat: ChatSummary, commands: ChatRowCommands): ChatRowLabels {
    val resources = androidx.compose.ui.platform.LocalContext.current.resources
    return ChatRowLabels(chat, commands) { resources.getString(it) }
}

/** The accessibility actions of the row menu: the same commands, Delete included (bipe-chat/bipe#587). */
private fun accessibilityActions(labels: ChatRowLabels, commands: ChatRowCommands, menu: MutableState<Boolean>) = buildList {
    add(CustomAccessibilityAction(labels.menu) { menu.value = true; true })
    add(CustomAccessibilityAction(labels.archive) { commands.onArchive(); true })
    commands.onFavourite?.let { favourite -> add(CustomAccessibilityAction(labels.favourite) { favourite(); true }) }
    if (!commands.readPending) add(CustomAccessibilityAction(labels.read) { commands.onRead(); true })
    add(CustomAccessibilityAction(labels.notifications) { commands.onNotifications(); true })
    commands.onInfo?.let { open -> add(CustomAccessibilityAction(labels.details) { open(); true }) }
    commands.onDelete?.let { delete -> add(CustomAccessibilityAction(labels.delete) { delete(); true }) }
}

/** The long-press menu of one chat (spec chat-row-actions). */
@Composable
fun ChatRowMenu(chat: ChatSummary, commands: ChatRowCommands, menu: MutableState<Boolean>) {
    val colors = BipeTheme.colors
    val labels = rowLabels(chat, commands)
    DropdownMenu(expanded = menu.value, onDismissRequest = { menu.value = false }) {
        DropdownMenuItem(text = { Text(labels.archive) }, onClick = { menu.value = false; commands.onArchive() })
        commands.onFavourite?.let { favourite ->
            DropdownMenuItem(text = { Text(labels.favourite) }, onClick = { menu.value = false; favourite() })
        }
        DropdownMenuItem(text = { Text(labels.read) }, enabled = !commands.readPending, onClick = { menu.value = false; commands.onRead() })
        DropdownMenuItem(text = { Text(labels.notifications) }, onClick = { menu.value = false; commands.onNotifications() })
        DropdownMenuItem(text = { Text(labels.details) }, enabled = commands.onInfo != null, onClick = { menu.value = false; commands.onInfo?.invoke() })
        commands.onDelete?.let { delete ->
            DropdownMenuItem(text = { Text(labels.delete, color = colors.trustDangerInk) }, onClick = { menu.value = false; delete() })
        }
    }
}

/** The menu state and accessibility actions of one chat, for a row or a favourites cell. */
@Composable
fun rememberChatRowMenu(ownerId: String, roomId: String): MutableState<Boolean> = remember(ownerId, roomId) { mutableStateOf(false) }

@Composable
fun Modifier.chatRowSemantics(chat: ChatSummary, commands: ChatRowCommands, menu: MutableState<Boolean>): Modifier {
    val labels = rowLabels(chat, commands)
    return semantics { customActions = accessibilityActions(labels, commands, menu) }
}

/** Swipe archives only; native long-press/accessibility menus expose account-owned row commands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatRow(chat: ChatSummary, media: MediaLoader, ownerId: String, commands: ChatRowCommands, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    val action = stringResource(if (commands.archived) R.string.bipe_unarchive_action else R.string.bipe_archive_action)
    val menu = rememberChatRowMenu(ownerId, chat.roomId)
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) commands.onArchive()
        false
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.chatRowSemantics(chat, commands, menu),
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(colors.surfaceBase).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Text(action, color = colors.accentFill)
            }
        },
    ) {
        Box(Modifier.background(colors.surfaceBase)) {
            ChatRow(chat, media, onLongClick = { menu.value = true }, actions = {
                Box { ChatRowMenu(chat, commands, menu) }
            }, onClick = onOpen)
        }
    }
}
