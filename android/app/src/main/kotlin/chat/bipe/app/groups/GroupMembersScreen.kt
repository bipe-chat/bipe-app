// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen

/** Spec group-members. Groups only (RoomInfo.isDm false, ADR 0141). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupMembersScreen(session: AccountSession, media: MediaLoader, roomId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(roomId) { GroupMembersModel(session, roomId, scope) }
    var inviting by remember { mutableStateOf(false) }
    var sheetMember by remember { mutableStateOf<GroupMember?>(null) }
    var confirm by remember { mutableStateOf<Pair<GroupMembersModel.MemberAction, GroupMember>?>(null) }
    val context = LocalContext.current
    val sentText = stringResource(R.string.bipe_dm_invite_to_group_sent)
    LaunchedEffect(model, inviting) { if (!inviting) model.appear() }
    if (inviting) {
        GroupInviteMembersScreen(session, media, GroupInviteMembersModel.Mode.Invite(roomId), onBack = { inviting = false }) {
            Toast.makeText(context, sentText, Toast.LENGTH_SHORT).show()
            inviting = false
        }
        return
    }
    BackHandler(onBack = onBack)

    val colors = BipeTheme.colors
    val query by model.query.collectAsState()
    val joined by model.joined.collectAsState()
    val invited by model.invited.collectAsState()
    val banned by model.banned.collectAsState()
    val rights by model.rights.collectAsState()
    val loading by model.loading.collectAsState()
    val error by model.error.collectAsState()
    val errorText = stringResource(R.string.bipe_app_group_member_error)
    LaunchedEffect(error) {
        if (error) {
            Toast.makeText(context, errorText, Toast.LENGTH_LONG).show()
            model.dismissError()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.GROUP_MEMBERS.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(stringResource(R.string.bipe_app_group_members_title), onBack)
            OutlinedTextField(
                value = query,
                onValueChange = model::setQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.bipe_app_people_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (rights.canInvite) {
                    item(key = "add") {
                        GroupRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_app_group_add_people)) { inviting = true }
                    }
                }
                if (loading) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accentFill)
                        }
                    }
                }
                if (joined.isNotEmpty()) {
                    item(key = "joined") { GroupSectionHeader(stringResource(R.string.bipe_app_group_members_joined)) }
                    items(joined, key = { "j" + it.person.userId }) { member ->
                        MemberRow(member, media) { if (model.actions(member).isNotEmpty()) sheetMember = member }
                    }
                }
                if (invited.isNotEmpty()) {
                    item(key = "invited") { GroupSectionHeader(stringResource(R.string.bipe_app_group_members_invited)) }
                    items(invited, key = { "i" + it.person.userId }) { member ->
                        MemberRow(member, media) { if (model.actions(member).isNotEmpty()) sheetMember = member }
                    }
                }
                if (banned.isNotEmpty()) {
                    item(key = "banned") { GroupSectionHeader(stringResource(R.string.bipe_app_group_members_banned)) }
                    items(banned, key = { "b" + it.person.userId }) { member ->
                        MemberRow(member, media) { if (model.actions(member).isNotEmpty()) sheetMember = member }
                    }
                }
            }
        }
    }

    sheetMember?.let { member ->
        ModalBottomSheet(onDismissRequest = { sheetMember = null }, containerColor = colors.surfaceRaised) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    member.person.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                model.actions(member).forEach { action ->
                    val destructive = action == GroupMembersModel.MemberAction.REMOVE ||
                        action == GroupMembersModel.MemberAction.BAN ||
                        action == GroupMembersModel.MemberAction.CANCEL_INVITE
                    Text(
                        stringResource(actionTitle(action)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (destructive) colors.trustDangerInk else colors.inkPrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                sheetMember = null
                                if (action == GroupMembersModel.MemberAction.REMOVE || action == GroupMembersModel.MemberAction.BAN ||
                                    action == GroupMembersModel.MemberAction.UNBAN
                                ) {
                                    confirm = action to member
                                } else {
                                    model.perform(action, member)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                Text(
                    stringResource(R.string.bipe_app_cancel),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.inkSecondary,
                    modifier = Modifier.fillMaxWidth().clickable { sheetMember = null }.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }

    confirm?.let { (action, member) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(actionTitle(action))) },
            text = {
                val body = when (action) {
                    GroupMembersModel.MemberAction.BAN -> R.string.bipe_app_group_member_ban_body
                    GroupMembersModel.MemberAction.UNBAN -> R.string.bipe_app_group_member_unban_body
                    else -> R.string.bipe_app_group_member_remove_body
                }
                Text(stringResource(body, member.person.displayName))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    model.perform(action, member)
                }) {
                    Text(
                        stringResource(actionTitle(action)),
                        color = if (action == GroupMembersModel.MemberAction.UNBAN) colors.accentInk else colors.trustDangerInk,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.bipe_app_cancel)) }
            },
        )
    }
}

private fun actionTitle(action: GroupMembersModel.MemberAction): Int = when (action) {
    GroupMembersModel.MemberAction.VIEW_PROFILE -> R.string.bipe_app_group_member_profile
    GroupMembersModel.MemberAction.MAKE_ADMIN -> R.string.bipe_app_group_member_make_admin
    GroupMembersModel.MemberAction.REMOVE_ADMIN -> R.string.bipe_app_group_member_remove_admin
    GroupMembersModel.MemberAction.REMOVE -> R.string.bipe_app_group_member_remove
    GroupMembersModel.MemberAction.BAN -> R.string.bipe_app_group_member_ban
    GroupMembersModel.MemberAction.UNBAN -> R.string.bipe_app_group_member_unban
    GroupMembersModel.MemberAction.CANCEL_INVITE -> R.string.bipe_app_group_member_cancel_invite
}

@Composable
private fun MemberRow(member: GroupMember, media: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(member.person.displayName, member.person.avatarUrl, media, size = 40.dp, userId = member.person.userId)
        Spacer(Modifier.width(12.dp))
        Text(
            member.person.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            member.isYou -> Text(stringResource(R.string.bipe_app_you), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            member.isAdmin -> Text(stringResource(R.string.bipe_app_group_admin), style = MaterialTheme.typography.bodySmall, color = colors.accentInk)
        }
    }
}
