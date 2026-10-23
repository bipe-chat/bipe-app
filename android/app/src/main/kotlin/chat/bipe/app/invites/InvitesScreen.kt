// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.Person
import chat.bipe.app.people.QrCode
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeInvite
import chat.bipe.shared.generated.BipeScreen
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.launch

data class InviteQr(val id: String, val url: String, val name: String? = null) {
    override fun toString(): String = "InviteQr($id)"
}

/** Spec invites, including the create-note and QR screens. Navigation stays inside the selected account. */
@Composable
fun InvitesScreen(model: InvitesModel, people: PeopleService, onBack: () -> Unit, openChat: (String) -> Unit) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<InviteQr?>(null) }
    var editing by remember { mutableStateOf<BipeInvite?>(null) }
    var name by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<BipeInvite?>(null) }
    LaunchedEffect(model) { model.refresh() }

    val code = qr
    when {
        code != null -> { InviteQrScreen(code) { qr = null }; return }
        creating -> { InviteNoteScreen(model, onBack = { creating = false }, showQr = { qr = it }); return }
    }
    BackHandler(onBack = onBack)
    PeopleScaffold(stringResource(R.string.bipe_invites_title), BipeScreen.INVITES.id, onBack) {
        Button(onClick = { model.beginCreate(); creating = true }, enabled = (state.quota?.available ?: 0) > 0) {
            Text(stringResource(R.string.bipe_invites_create))
        }
        val available = state.quota?.available
        if (available != null && available <= 0) {
            Text(stringResource(R.string.bipe_invites_none_left))
            state.quota?.nextAvailableAt?.let { Text(stringResource(R.string.bipe_invites_next, dateLabel(it))) }
        } else {
            Text(stringResource(R.string.bipe_invites_explainer), color = BipeTheme.colors.inkSecondary)
            if (available != null && available <= 5) Text(stringResource(R.string.bipe_invites_available, available.toString()))
        }
        if (state.phase == InvitesModel.Phase.LOADING) CircularProgressIndicator()
        if (state.phase == InvitesModel.Phase.FAILED) {
            Text(stringResource(R.string.bipe_error_network), color = BipeTheme.colors.trustDangerInk)
            TextButton(onClick = { scope.launch { model.refresh() } }) { Text(stringResource(R.string.bipe_app_retry)) }
        }
        state.error?.let { Text(it, color = BipeTheme.colors.trustDangerInk) }
        if (state.phase == InvitesModel.Phase.LOADED && state.pending.isEmpty() && state.joined.isEmpty()) {
            Text(stringResource(R.string.bipe_invites_empty))
        }
        if (state.pending.isNotEmpty()) Text(stringResource(R.string.bipe_invites_pending_header), style = MaterialTheme.typography.titleMedium)
        state.pending.forEach { invite ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(invite.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bipe_invites_status_pending))
                Text(stringResource(R.string.bipe_invites_expires, dateLabel(invite.expiresAt)), style = MaterialTheme.typography.bodySmall)
                if (invite.id in state.notes) Text(stringResource(R.string.bipe_invite_note_title), style = MaterialTheme.typography.bodySmall)
                val link = state.links[invite.id]
                if (link == null) Text(stringResource(R.string.bipe_invites_other_device), color = BipeTheme.colors.inkSecondary)
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { shareInvite(context, link) }) { Text(stringResource(R.string.bipe_app_share)) }
                    TextButton(onClick = { qr = InviteQr(invite.id, link, invite.name) }) { Text(stringResource(R.string.bipe_invites_qr_action)) }
                }
                Row {
                    TextButton(onClick = { name = invite.name.orEmpty(); editing = invite }) { Text(stringResource(R.string.bipe_invites_name_action)) }
                    TextButton(onClick = { revoking = invite }) { Text(stringResource(R.string.bipe_invites_revoke), color = BipeTheme.colors.trustDangerInk) }
                }
                HorizontalDivider()
            }
        }
        if (state.joined.isNotEmpty()) Text(stringResource(R.string.bipe_invites_joined_header), style = MaterialTheme.typography.titleMedium)
        state.joined.forEach { JoinedInviteRow(it, people, openChat) }
    }
    editing?.let { invite ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.bipe_invites_name_action)) },
            text = { Column {
                OutlinedTextField(name, onValueChange = { name = it }, label = { Text(stringResource(R.string.bipe_invites_name_placeholder)) })
                Text(stringResource(R.string.bipe_invites_name_footer))
            } },
            confirmButton = { TextButton(onClick = { editing = null; scope.launch { model.setName(invite.id, name) } }) { Text(stringResource(R.string.bipe_app_save)) } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
    revoking?.let { invite ->
        AlertDialog(onDismissRequest = { revoking = null }, title = { Text(stringResource(R.string.bipe_invites_revoke)) },
            confirmButton = { TextButton(onClick = { revoking = null; scope.launch { model.revoke(invite) } }) { Text(stringResource(R.string.bipe_invites_revoke)) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
}

@Composable
private fun JoinedInviteRow(invite: BipeInvite, people: PeopleService, openChat: (String) -> Unit) {
    var person by remember(invite.joinedUserId) { mutableStateOf<Person?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(invite.joinedUserId) { invite.joinedUserId?.let { person = people.person(it) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(person?.displayName.orEmpty(), person?.avatarUrl, people.media, size = 40.dp, userId = invite.joinedUserId)
        Spacer(Modifier.width(12.dp))
        Text(person?.displayName ?: stringResource(R.string.bipe_invites_status_used), modifier = Modifier.weight(1f))
        TextButton(enabled = !busy, onClick = {
            val userId = invite.joinedUserId ?: return@TextButton
            busy = true
            scope.launch {
                try { openChat(people.openOrCreateDm(userId)) }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
        }) { Text(stringResource(if (failed) R.string.bipe_app_retry else R.string.bipe_invites_chat)) }
    }
}

/** Spec invite-note. A retry after creation never repeats POST /invites. */
@Composable
private fun InviteNoteScreen(model: InvitesModel, onBack: () -> Unit, showQr: (InviteQr) -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultNote = stringResource(R.string.bipe_invite_note_default)
    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(defaultNote) }
    var busy by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    BackHandler { if (!busy) onBack() }
    PeopleScaffold(stringResource(R.string.bipe_invites_create), BipeScreen.INVITE_NOTE.id, onBack = { if (!busy) onBack() }) {
        val created = state.created
        if (created != null) {
            Text(created.url)
            Text(stringResource(R.string.bipe_invites_expires, dateLabel(created.expiresAt)))
            TextButton(onClick = { shareInvite(context, created.url) }) { Text(stringResource(R.string.bipe_app_share)) }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, created.url))
                copied = true
            }) { Text(stringResource(R.string.bipe_app_copy)) }
            TextButton(onClick = { showQr(InviteQr(created.id, created.url, name)) }) { Text(stringResource(R.string.bipe_invites_qr_action)) }
        } else {
            OutlinedTextField(name, onValueChange = { name = it }, enabled = !busy,
                label = { Text(stringResource(R.string.bipe_invites_name_placeholder)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.bipe_invites_name_footer), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(note, onValueChange = { note = it }, enabled = !busy, minLines = 3,
                label = { Text(stringResource(R.string.bipe_invite_note_title)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.bipe_invite_note_footer), style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { Text(it, color = BipeTheme.colors.trustDangerInk) }
        if (created == null || state.error != null) Button(enabled = !busy, onClick = {
            busy = true
            scope.launch { try { model.create(name, note) } finally { busy = false } }
        }) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(if (busy) R.string.bipe_app_invite_creating else if (created == null) R.string.bipe_invites_create else R.string.bipe_app_retry))
        }
        if (copied) Text(stringResource(R.string.bipe_app_copied))
    }
}

@Composable
private fun InviteQrScreen(invite: InviteQr, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    PeopleScaffold(stringResource(R.string.bipe_invites_qr_title), BipeScreen.INVITE_QR.id, onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            invite.name?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            QrCode(invite.url)
            Text(stringResource(R.string.bipe_invites_qr_footer))
            TextButton(onClick = { shareInvite(context, invite.url) }) { Text(stringResource(R.string.bipe_app_share)) }
        }
    }
}

private fun shareInvite(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bipe_invites_share_message, link))
    context.startActivity(Intent.createChooser(send, null))
}

private fun dateLabel(raw: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date.from(Instant.parse(raw)))
}.getOrDefault(raw)
