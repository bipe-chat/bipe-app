// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.invites

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import chat.bipe.app.common.BipeButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.common.BipeOutlinedButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.people.PeopleScaffold
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.Person
import chat.bipe.app.people.QrCode
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.BipeInviteLink
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeInvite
import chat.bipe.shared.generated.BipeScreen
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.launch

/** One invite as the card shows it: a new one from create, or a pending one from the list. */
data class InviteCard(val id: String, val url: String?, val expiresAt: String) {
    override fun toString(): String = "InviteCard($id)"
}

/** Spec invites: one create button, then two lists. A pending row opens its card; a joined row opens the chat. */
@Composable
fun InvitesScreen(model: InvitesModel, people: PeopleService, onBack: () -> Unit, openChat: (String) -> Unit) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf<InviteCard?>(null) }
    var renaming by remember { mutableStateOf<BipeInvite?>(null) }
    var name by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf<BipeInvite?>(null) }
    val context = LocalContext.current
    LaunchedEffect(model) { model.refresh() }

    val open = card
    when {
        open != null -> { InviteCardScreen(model, open) { card = null }; return }
        creating -> { InviteCreateScreen(model, onBack = { creating = false }); return }
    }
    BackHandler(onBack = onBack)
    PeopleScaffold(stringResource(R.string.bipe_invites_title), BipeScreen.INVITES.id, onBack) {
        BipeButton(onClick = { model.beginCreate(); creating = true }, enabled = (state.quota?.available ?: 0) > 0, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(PhosphorIcon.PLUS.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.bipe_invites_create))
        }
        val available = state.quota?.available
        val footer = if (available != null && available <= 0) {
            listOfNotNull(stringResource(R.string.bipe_invites_none_left),
                state.quota?.nextAvailableAt?.let { stringResource(R.string.bipe_invites_next, dateLabel(it)) })
        } else {
            listOfNotNull(stringResource(R.string.bipe_invites_explainer),
                available?.takeIf { it <= 5 }?.let { stringResource(R.string.bipe_invites_available, it.toString()) })
        }
        Text(footer.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary)
        if (state.phase == InvitesModel.Phase.LOADING) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        if (state.phase == InvitesModel.Phase.FAILED) {
            Text(stringResource(R.string.bipe_error_network), color = BipeTheme.colors.trustDangerInk)
            TextButton(onClick = { scope.launch { model.refresh() } }) { Text(stringResource(R.string.bipe_app_retry)) }
        }
        state.error?.let { Text(it, color = BipeTheme.colors.trustDangerInk) }
        if (state.phase == InvitesModel.Phase.LOADED && state.pending.isEmpty() && state.joined.isEmpty()) {
            Text(stringResource(R.string.bipe_invites_empty), color = BipeTheme.colors.inkSecondary)
        }
        if (state.pending.isNotEmpty()) Text(stringResource(R.string.bipe_invites_pending_header), style = MaterialTheme.typography.titleMedium)
        state.pending.forEach { invite ->
            val link = state.links[invite.id]
            PendingInviteRow(invite, link,
                onShare = { url -> shareInvite(context, url) },
                onCopy = { url -> context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, url)) },
                onRename = { name = invite.name.orEmpty(); renaming = invite },
                onRevoke = { revoking = invite },
            ) { card = InviteCard(invite.id, link, invite.expiresAt) }
        }
        if (state.joined.isNotEmpty()) Text(stringResource(R.string.bipe_invites_joined_header), style = MaterialTheme.typography.titleMedium)
        state.joined.forEach { JoinedInviteRow(it, people, openChat) }
    }
    renaming?.let { invite ->
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.bipe_invites_name_action)) },
            text = { Column {
                OutlinedTextField(name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.bipe_invites_name_placeholder)) })
                Text(stringResource(R.string.bipe_invites_name_footer))
            } },
            confirmButton = { TextButton(onClick = { renaming = null; scope.launch { model.setName(invite.id, name) } }) { Text(stringResource(R.string.bipe_app_save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
    revoking?.let { invite ->
        AlertDialog(onDismissRequest = { revoking = null }, title = { Text(stringResource(R.string.bipe_invites_revoke)) },
            confirmButton = { TextButton(onClick = { revoking = null; scope.launch { model.revoke(invite) } }) { Text(stringResource(R.string.bipe_invites_revoke)) } },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
}

/** Long press opens the invite-qr actions without the card: share and copy need the link on this phone. */
@Composable
private fun PendingInviteRow(
    invite: BipeInvite, link: String?, onShare: (String) -> Unit, onCopy: (String) -> Unit,
    onRename: () -> Unit, onRevoke: () -> Unit, onClick: () -> Unit,
) {
    var menu by remember(invite.id) { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.bipe_invites_actions)
    val shareLabel = stringResource(R.string.bipe_app_share)
    val copyLabel = stringResource(R.string.bipe_app_copy)
    val renameLabel = stringResource(R.string.bipe_invites_name_action)
    val revokeLabel = stringResource(R.string.bipe_invites_revoke)
    Box {
        Row(Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = menuLabel)
            .semantics {
                customActions = buildList {
                    link?.let { url ->
                        add(CustomAccessibilityAction(shareLabel) { onShare(url); true })
                        add(CustomAccessibilityAction(copyLabel) { onCopy(url); true })
                    }
                    add(CustomAccessibilityAction(renameLabel) { onRename(); true })
                    add(CustomAccessibilityAction(revokeLabel) { onRevoke(); true })
                }
            }
            .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(invite.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bipe_invites_link))
                Text(if (link != null) stringResource(R.string.bipe_invites_expires, dateLabel(invite.expiresAt)) else stringResource(R.string.bipe_invites_other_device),
                    style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary)
            }
            Icon(painterResource(PhosphorIcon.CARET_RIGHT.drawable), contentDescription = null, tint = BipeTheme.colors.inkSecondary, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            link?.let { url ->
                DropdownMenuItem(text = { Text(shareLabel) }, onClick = { menu = false; onShare(url) })
                DropdownMenuItem(text = { Text(copyLabel) }, onClick = { menu = false; onCopy(url) })
            }
            DropdownMenuItem(text = { Text(renameLabel) }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text(revokeLabel, color = BipeTheme.colors.trustDangerInk) }, onClick = { menu = false; onRevoke() })
        }
    }
    HorizontalDivider()
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

/** Spec invite-note, without the note: ask who it is for, create, then show the card. A retry never repeats POST /invites. */
@Composable
private fun InviteCreateScreen(model: InvitesModel, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val created = state.created
    if (created != null && state.error == null) {
        InviteCardScreen(model, InviteCard(created.id, created.url, created.expiresAt), onBack)
        return
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { if (!busy) onBack() }
    PeopleScaffold(stringResource(R.string.bipe_invites_name_action), BipeScreen.INVITE_NOTE.id, onBack = { if (!busy) onBack() }) {
        OutlinedTextField(name, onValueChange = { name = it }, enabled = !busy, singleLine = true,
            label = { Text(stringResource(R.string.bipe_invites_name_placeholder)) }, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        Text(stringResource(R.string.bipe_invites_name_footer), style = MaterialTheme.typography.bodySmall, color = BipeTheme.colors.inkSecondary)
        state.error?.let { Text(it, color = BipeTheme.colors.trustDangerInk) }
        BipeButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true
            scope.launch { try { model.create(name) } finally { busy = false } }
        }) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(if (busy) R.string.bipe_app_invite_creating else if (created == null) R.string.bipe_invites_create else R.string.bipe_app_retry))
        }
    }
}

/** One invite: the QR code and the link to share, then rename and revoke. A new invite and a pending one look the same. */
@Composable
private fun InviteCardScreen(model: InvitesModel, card: InviteCard, onBack: () -> Unit) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invite = state.pending.firstOrNull { it.id == card.id }
    var copied by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var revoking by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val title = invite?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.bipe_invites_link)
    PeopleScaffold(title, BipeScreen.INVITE_QR.id, onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val url = card.url
            if (url != null) {
                QrCode(url)
                // State code: the other person can read it and type it on signup-invite-code.
                BipeInviteLink.normalizedCode(url)?.let { code ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.bipe_signup_invite_code), style = MaterialTheme.typography.bodyMedium, color = BipeTheme.colors.inkSecondary)
                        SelectionContainer {
                            Text(code, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace, color = BipeTheme.colors.inkPrimary)
                        }
                    }
                }
                Text(stringResource(R.string.bipe_invites_expires, dateLabel(card.expiresAt)), color = BipeTheme.colors.inkSecondary)
                BipeButton(onClick = { shareInvite(context, url) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(PhosphorIcon.SHARE_NETWORK.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bipe_app_share))
                }
                BipeOutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, url))
                    copied = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(PhosphorIcon.COPY.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (copied) R.string.bipe_app_copied else R.string.bipe_app_copy))
                }
                Text(stringResource(R.string.bipe_invites_qr_footer), style = MaterialTheme.typography.bodySmall,
                    color = BipeTheme.colors.inkSecondary, textAlign = TextAlign.Center)
            } else {
                Text(stringResource(R.string.bipe_invites_other_device), color = BipeTheme.colors.inkSecondary)
            }
            if (invite != null) {
                HorizontalDivider()
                TextButton(onClick = { name = invite.name.orEmpty(); editing = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_invites_name_action))
                }
                TextButton(onClick = { revoking = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bipe_invites_revoke), color = BipeTheme.colors.trustDangerInk)
                }
            }
        }
    }
    if (editing && invite != null) {
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.bipe_invites_name_action)) },
            text = { Column {
                OutlinedTextField(name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.bipe_invites_name_placeholder)) })
                Text(stringResource(R.string.bipe_invites_name_footer))
            } },
            confirmButton = { TextButton(onClick = { editing = false; scope.launch { model.setName(invite.id, name) } }) { Text(stringResource(R.string.bipe_app_save)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
    if (revoking && invite != null) {
        AlertDialog(onDismissRequest = { revoking = false }, title = { Text(stringResource(R.string.bipe_invites_revoke)) },
            confirmButton = { TextButton(onClick = { revoking = false; scope.launch { model.revoke(invite); onBack() } }) { Text(stringResource(R.string.bipe_invites_revoke)) } },
            dismissButton = { TextButton(onClick = { revoking = false }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
}

private fun shareInvite(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bipe_invites_share_message, link))
    context.startActivity(Intent.createChooser(send, null))
}

private fun dateLabel(raw: String): String = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(raw)))
}.getOrDefault(raw)
