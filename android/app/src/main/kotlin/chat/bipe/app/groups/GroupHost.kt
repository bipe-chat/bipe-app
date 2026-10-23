// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import chat.bipe.app.invites.InvitesScreen
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R

/** What the group screens on top of the home screen need from it. */
class GroupHostActions(
    val createGroup: () -> Unit,
    val openGroupDetails: () -> Unit,
    val deleteDm: (String) -> Unit,
    val openInvites: () -> Unit,
    /** group-create with these people picked (person-profile, action newGroup). */
    val createGroupWith: (List<Person>) -> Unit = {},
    /** chat-notifications for a room (person-profile, action notifications). */
    val openNotifications: (String) -> Unit = {},
    val openDetailsForRoom: (String) -> Unit = {},
)

/** The Bipe API client for the screens behind GroupHost that need it (invites from group-invite-members). */
val LocalBipeApi = staticCompositionLocalOf<BipeApiClient?> { null }

/**
 * The group and DM screens over chat-list and chat-timeline: group-create, group-details (with the screens behind
 * it) and dm-delete. Also runs the account work of these specs: quiet groups once per account, and the cleared-DM
 * markers at start and on each return to the foreground. content draws chat-list or chat-timeline.
 */
@Composable
fun GroupHost(
    session: AccountSession,
    api: BipeApiClient,
    media: MediaLoader,
    roomId: String?,
    foregroundCount: Int,
    onOpenRoom: (String?) -> Unit,
    content: @Composable (GroupHostActions) -> Unit,
) {
    val context = LocalContext.current
    var creating by rememberSaveable { mutableStateOf(false) }
    var creatingWith by remember { mutableStateOf(emptyList<Person>()) }
    var notificationsRoom by rememberSaveable(session.userId) { mutableStateOf<String?>(null) }
    var detailsRoom by rememberSaveable(session.userId) { mutableStateOf<String?>(null) }
    var deletingDm by rememberSaveable(session.userId) { mutableStateOf<String?>(null) }
    var showingInvites by rememberSaveable { mutableStateOf(false) }
    val invitesFailed = stringResource(R.string.bipe_app_group_create_invites_failed)
    LaunchedEffect(session) { QuietGroups.applyOnce(context, session) }
    LaunchedEffect(session, foregroundCount) { ClearedChatsStore.refresh(session.client) }

    deletingDm?.let { dm ->
        DmDeleteSheet(session, dm, onDismiss = { deletingDm = null }, onDeleted = {
            deletingDm = null
            if (dm == roomId) onOpenRoom(null)
        })
    }
    val actions = GroupHostActions(
        createGroup = { creating = true },
        openGroupDetails = { detailsRoom = roomId },
        openDetailsForRoom = { detailsRoom = it },
        deleteDm = { deletingDm = it },
        openInvites = { showingInvites = true },
        createGroupWith = {
            creatingWith = it
            creating = true
        },
        openNotifications = { notificationsRoom = it },
    )
    CompositionLocalProvider(LocalBipeApi provides api) {
        when {
            showingInvites -> InvitesScreen(session, api, media, onClose = { showingInvites = false }, onOpenChat = onOpenRoom)
            creating -> GroupCreateScreen(
                session,
                media,
                onClose = {
                    creating = false
                    creatingWith = emptyList()
                },
                onCreated = { newRoomId, allInvited ->
                    creating = false
                    creatingWith = emptyList()
                    onOpenRoom(newRoomId)
                    if (!allInvited) Toast.makeText(context, invitesFailed, Toast.LENGTH_LONG).show()
                },
                people = creatingWith,
            )
            notificationsRoom != null -> {
                BackHandler { notificationsRoom = null }
                ChatNotificationsScreen(session, notificationsRoom!!, onBack = { notificationsRoom = null })
            }
            detailsRoom != null -> GroupDetailsScreen(
                session,
                media,
                detailsRoom!!,
                onBack = { detailsRoom = null },
                onLeft = {
                    detailsRoom = null
                    onOpenRoom(null)
                },
            )
            else -> content(actions)
        }
    }
}

/** The plus menu of chat-list: group-create and invites (the Grupos and Você tabs take them over, lane D). */
@Composable
fun CreateGroupButton(onCreateGroup: () -> Unit, onInvite: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.padding(end = 4.dp)) {
            Icon(
                painterResource(PhosphorIcon.PLUS.drawable),
                contentDescription = stringResource(R.string.bipe_groups_empty_action),
                tint = BipeTheme.colors.glyphPrimary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_groups_empty_action)) }, onClick = {
                open = false
                onCreateGroup()
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.bipe_invites_title)) }, onClick = {
                open = false
                onInvite()
            })
        }
    }
}

/** Spec dm-delete: a cleared DM stays out of chat-list until a message after its marker. lastActivity in ms. */
@Composable
fun <T> withoutClearedDms(chats: List<T>, roomId: (T) -> String, lastActivity: (T) -> Long?): List<T> {
    val markers by ClearedChatsStore.markers.collectAsState()
    return chats.filterNot { markers.isHidden(roomId(it), lastActivity(it)) }
}
