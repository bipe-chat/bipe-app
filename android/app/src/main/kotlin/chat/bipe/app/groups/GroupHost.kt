// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** What the group screens on top of the home screen need from it. */
class GroupHostActions(val createGroup: () -> Unit, val openGroupDetails: () -> Unit, val deleteDm: (String) -> Unit)

/**
 * The group and DM screens over chat-list and chat-timeline: group-create, group-details (with the screens behind
 * it) and dm-delete. Also runs the account work of these specs: quiet groups once per account, and the cleared-DM
 * markers at start and on each return to the foreground. content draws chat-list or chat-timeline.
 */
@Composable
fun GroupHost(
    session: AccountSession,
    media: MediaLoader,
    roomId: String?,
    foregroundCount: Int,
    onOpenRoom: (String?) -> Unit,
    content: @Composable (GroupHostActions) -> Unit,
) {
    val context = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    var deletingDm by rememberSaveable { mutableStateOf<String?>(null) }
    val invitesFailed = stringResource(R.string.bipe_app_group_create_invites_failed)
    LaunchedEffect(session) { QuietGroups.applyOnce(context, session) }
    LaunchedEffect(session, foregroundCount) { ClearedChatsStore.refresh(session.client) }

    deletingDm?.let { dm ->
        DmDeleteSheet(session, dm, onDismiss = { deletingDm = null }, onDeleted = {
            deletingDm = null
            if (dm == roomId) onOpenRoom(null)
        })
    }
    when {
        creating -> GroupCreateScreen(
            session,
            media,
            onClose = { creating = false },
            onCreated = { newRoomId, allInvited ->
                creating = false
                onOpenRoom(newRoomId)
                if (!allInvited) Toast.makeText(context, invitesFailed, Toast.LENGTH_LONG).show()
            },
        )
        roomId != null && detailsOpen -> GroupDetailsScreen(
            session,
            media,
            roomId,
            onBack = { detailsOpen = false },
            onLeft = {
                detailsOpen = false
                onOpenRoom(null)
            },
        )
        else -> content(GroupHostActions(createGroup = { creating = true }, openGroupDetails = { detailsOpen = true }, deleteDm = { deletingDm = it }))
    }
}

/** The plus button of chat-list that opens group-create (the Grupos tab takes it over, lane D). */
@Composable
fun CreateGroupButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(end = 4.dp)) {
        Icon(
            painterResource(PhosphorIcon.PLUS.drawable),
            contentDescription = stringResource(R.string.bipe_groups_empty_action),
            tint = BipeTheme.colors.glyphPrimary,
        )
    }
}

/** Spec dm-delete: a cleared DM stays out of chat-list until a message after its marker. lastActivity in ms. */
@Composable
fun <T> withoutClearedDms(chats: List<T>, roomId: (T) -> String, lastActivity: (T) -> Long?): List<T> {
    val markers by ClearedChatsStore.markers.collectAsState()
    return chats.filterNot { markers.isHidden(roomId(it), lastActivity(it)) }
}

/** A chat-list row that a swipe to the left offers to delete, for a DM (spec dm-delete notes). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmSwipeRow(isDm: Boolean, onDelete: () -> Unit, row: @Composable () -> Unit) {
    if (!isDm) {
        row()
        return
    }
    val colors = BipeTheme.colors
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        },
    )
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(colors.trustDangerWash).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                Text(stringResource(R.string.bipe_dm_delete_action), color = colors.trustDangerInk)
            }
        },
    ) {
        Box(Modifier.background(colors.surfaceBase)) { row() }
    }
}
