// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.groups.GroupFooter
import chat.bipe.app.groups.GroupRow
import chat.bipe.app.groups.GroupSectionHeader
import chat.bipe.app.groups.GroupTopBar
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen

/** The system share sheet with bipe_invites_share_message and the link. */
private fun shareInvite(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bipe_invites_share_message, link))
    context.startActivity(Intent.createChooser(send, null))
}

/** Spec invites. onOpenChat opens the DM with a person who joined. */
@Composable
fun InvitesScreen(session: AccountSession, api: BipeApiClient, media: MediaLoader, onClose: () -> Unit, onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { InvitesModel(context, session, api, scope) }
    var creating by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf<PersonalInvite?>(null) }
    var revoking by remember { mutableStateOf<PersonalInvite?>(null) }
    LaunchedEffect(model, creating) { if (!creating) model.appear() }
    if (creating) {
        InviteNoteScreen(session, api, onClose = { creating = false })
        return
    }
    BackHandler(onBack = onClose)
    val colors = BipeTheme.colors
    val state by model.state.collectAsState()
    val error by model.error.collectAsState()
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            model.dismissError()
        }
    }

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.INVITES.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_invites_title), onBack = onClose, backIcon = PhosphorIcon.X)
            when (state.phase) {
                InvitesModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
                InvitesModel.Phase.FAILED -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.bipe_error_network), color = colors.inkSecondary)
                    Button(onClick = model::appear) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                InvitesModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item(key = "create") {
                        if ((state.available ?: 0) > 0) {
                            GroupRow(PhosphorIcon.PLUS, stringResource(R.string.bipe_invites_create)) { creating = true }
                        }
                        GroupFooter(model.footer(state))
                    }
                    if (state.pending.isEmpty() && state.joined.isEmpty()) {
                        item(key = "empty") { GroupFooter(stringResource(R.string.bipe_invites_empty)) }
                    }
                    if (state.pending.isNotEmpty()) {
                        item(key = "pending") { GroupSectionHeader(stringResource(R.string.bipe_invites_pending_header)) }
                        items(state.pending, key = { "p" + it.id }) { invite ->
                            PendingRow(invite, onShare = { link -> shareInvite(context, link) }, onName = { naming = invite }, onRevoke = { revoking = invite })
                        }
                    }
                    if (state.joined.isNotEmpty()) {
                        item(key = "joined") { GroupSectionHeader(stringResource(R.string.bipe_invites_joined_header)) }
                        items(state.joined, key = { "j" + it.id }) { invite ->
                            JoinedRow(invite, media, model::dmRoomId) { roomId ->
                                onClose()
                                onOpenChat(roomId)
                            }
                        }
                    }
                }
            }
        }
    }

    naming?.let { invite ->
        var text by remember(invite.id) { mutableStateOf(invite.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(stringResource(R.string.bipe_invites_name_action)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.bipe_invites_name_placeholder)) },
                    )
                    Text(stringResource(R.string.bipe_invites_name_footer), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    model.setName(invite.id, text)
                    naming = null
                }) { Text(stringResource(R.string.bipe_app_save)) }
            },
            dismissButton = { TextButton(onClick = { naming = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
    revoking?.let { invite ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            text = { Text(stringResource(R.string.bipe_invites_revoke)) },
            confirmButton = {
                TextButton(onClick = {
                    model.revoke(invite.id)
                    revoking = null
                }) { Text(stringResource(R.string.bipe_invites_revoke), color = colors.trustDangerInk) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.bipe_app_cancel)) } },
        )
    }
}

@Composable
private fun PendingRow(invite: PersonalInvite, onShare: (String) -> Unit, onName: () -> Unit, onRevoke: () -> Unit) {
    val colors = BipeTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(invite.name ?: stringResource(R.string.bipe_invites_status_pending), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
        Text(stringResource(R.string.bipe_invites_expires, invite.expiresAt.dateText()), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        if (invite.link == null) {
            Text(stringResource(R.string.bipe_invites_other_device), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            invite.link?.let { link -> TextButton(onClick = { onShare(link) }) { Text(stringResource(R.string.bipe_app_share)) } }
            TextButton(onClick = onName) { Text(stringResource(R.string.bipe_invites_name_action)) }
            TextButton(onClick = onRevoke) { Text(stringResource(R.string.bipe_invites_revoke), color = colors.trustDangerInk) }
        }
    }
}

@Composable
private fun JoinedRow(invite: PersonalInvite, media: MediaLoader, dmRoomId: (String) -> String?, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val person = invite.joined
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person?.displayName.orEmpty(), person?.avatarUrl, media, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(person?.displayName ?: invite.name ?: stringResource(R.string.bipe_invites_status_used), style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
            invite.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary) }
        }
        val roomId = person?.userId?.let(dmRoomId)
        if (roomId != null) {
            TextButton(onClick = { onOpenChat(roomId) }) { Text(stringResource(R.string.bipe_invites_chat)) }
        }
    }
}

/** Spec invite-note. */
@Composable
fun InviteNoteScreen(session: AccountSession, api: BipeApiClient, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session) { InviteNoteModel(context, session, api, scope) }
    BackHandler(onBack = onClose)
    val colors = BipeTheme.colors
    val name by model.name.collectAsState()
    val note by model.note.collectAsState()
    val phase by model.phase.collectAsState()
    val creating = phase == InviteNoteModel.Phase.Creating
    val copiedText = stringResource(R.string.bipe_app_copied)

    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.INVITE_NOTE.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            GroupTopBar(stringResource(R.string.bipe_invites_create), onBack = onClose, backIcon = PhosphorIcon.X) {
                if (phase !is InviteNoteModel.Phase.Created) {
                    if (creating) {
                        CircularProgressIndicator(color = colors.accentFill, modifier = Modifier.size(24.dp).padding(end = 4.dp))
                    } else {
                        TextButton(onClick = model::create) { Text(stringResource(R.string.bipe_invites_create)) }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when (val current = phase) {
                    is InviteNoteModel.Phase.Created -> {
                        GroupSectionHeader(stringResource(R.string.bipe_invites_link))
                        Text(
                            current.link,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            color = colors.inkPrimary,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        GroupFooter(stringResource(R.string.bipe_invites_expires, current.expiresAt.dateText()))
                        Row(modifier = Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { shareInvite(context, current.link) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentFill, contentColor = colors.inkOnAccent),
                            ) { Text(stringResource(R.string.bipe_app_share)) }
                            TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, current.link))
                                Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                            }) { Text(stringResource(R.string.bipe_app_copy)) }
                        }
                    }
                    else -> {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { model.name.value = it },
                            enabled = !creating,
                            singleLine = true,
                            label = { Text(stringResource(R.string.bipe_invites_name_action)) },
                            placeholder = { Text(stringResource(R.string.bipe_invites_name_placeholder)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        GroupFooter(stringResource(R.string.bipe_invites_name_footer))
                        OutlinedTextField(
                            value = note,
                            onValueChange = { model.note.value = it },
                            enabled = !creating,
                            minLines = 3,
                            maxLines = 8,
                            label = { Text(stringResource(R.string.bipe_invite_note_title)) },
                            placeholder = { Text(stringResource(R.string.bipe_invite_note_placeholder)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        GroupFooter(stringResource(R.string.bipe_invite_note_footer))
                        when (current) {
                            InviteNoteModel.Phase.Creating -> GroupFooter(stringResource(R.string.bipe_app_invite_creating))
                            is InviteNoteModel.Phase.Failed -> GroupFooter(current.message, color = colors.trustDangerInk)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}
