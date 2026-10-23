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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** Swipe archives only; native context/visible overflow menus expose the selected account-owned row commands. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedChatRow(
    chat: ChatSummary, media: MediaLoader, ownerId: String, archived: Boolean, readPending: Boolean,
    onArchive: () -> Unit, onOpen: () -> Unit, onRead: () -> Unit, onNotifications: () -> Unit,
    onInfo: (() -> Unit)?, onDelete: (() -> Unit)?,
) {
    val colors = BipeTheme.colors
    val action = stringResource(if (archived) R.string.bipe_unarchive_action else R.string.bipe_archive_action)
    val readLabel = stringResource(if (chat.isUnread) R.string.bipe_app_notification_mark_read else R.string.bipe_chat_mark_unread)
    val notificationsLabel = stringResource(R.string.bipe_settings_notifications)
    val detailsLabel = stringResource(R.string.bipe_chat_details)
    var menu by remember(ownerId, chat.roomId) { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.EndToStart) onArchive()
        false
    })
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(action) { onArchive(); true })
                if (!readPending) add(CustomAccessibilityAction(readLabel) { onRead(); true })
                add(CustomAccessibilityAction(notificationsLabel) { onNotifications(); true })
                onInfo?.let { open -> add(CustomAccessibilityAction(detailsLabel) { open(); true }) }
            }
        },
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(colors.surfaceBase).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Text(action, color = colors.accentFill)
            }
        },
    ) {
        Box(Modifier.background(colors.surfaceBase)) {
            ChatRow(chat, media, onLongClick = { menu = true }, actions = {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(PhosphorIcon.DOTS_THREE.drawable), contentDescription = stringResource(R.string.bipe_chat_actions), tint = colors.glyphPrimary)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(action) }, onClick = { menu = false; onArchive() })
                        DropdownMenuItem(text = { Text(readLabel) }, enabled = !readPending, onClick = { menu = false; onRead() })
                        DropdownMenuItem(text = { Text(notificationsLabel) }, onClick = { menu = false; onNotifications() })
                        DropdownMenuItem(text = { Text(detailsLabel) }, enabled = onInfo != null, onClick = { menu = false; onInfo?.invoke() })
                        onDelete?.let { delete ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_dm_delete_action), color = colors.trustDangerInk) }, onClick = { menu = false; delete() })
                        }
                    }
                }
            }, onClick = onOpen)
        }
    }
}
