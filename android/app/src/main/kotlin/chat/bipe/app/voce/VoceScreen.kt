// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.app.tabs.AccountRows
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsHost
import chat.bipe.app.settings.SettingsRootSection
import chat.bipe.app.people.PeopleRoute
import chat.bipe.shared.generated.BipeSettingsGroup
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen
import chat.bipe.phosphor.PhosphorIcon
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID. */
@Composable
fun VoceScreen(tabs: AppTabsModel, session: AccountSession, api: BipeApiClient, media: MediaLoader, onNewAccount: () -> Unit, onOpenChat: (String) -> Unit) {
    val state by tabs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var openingNotes by remember { mutableStateOf(false) }
    var notesFailed by remember { mutableStateOf(false) }
    val notesTitle = stringResource(R.string.bipe_self_chat_title)
    val openNotes: () -> Unit = {
        if (!openingNotes) {
            openingNotes = true
            scope.launch {
                try { onOpenChat(tabs.chatList(session).openNotes(notesTitle)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { notesFailed = true }
                finally { openingNotes = false }
            }
        }
    }
    SettingsHost(session, api, invites = tabs.invites(session), onClose = { tabs.selectTab(AppTabsModel.Tab.CONVERSAS) }, onSignedOut = { tabs.start.completeSignOut(session.userId) },
        onOpenChat = onOpenChat, onProfileChanged = tabs::refreshProfiles,
        rootContent = { settings, openGroup, openPeople, openInvites ->
            Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.VOCE.id), color = BipeTheme.colors.surfaceBase) {
                LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    item { Text(stringResource(R.string.bipe_tab_you), style = MaterialTheme.typography.headlineMedium,
                        color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                    item { state.accounts.firstOrNull { it.isCurrent }?.let {
                        VoceHeader(it, media, openProfile = { openGroup(BipeSettingsGroup.PROFILE) },
                            openQr = { openPeople(PeopleRoute.ProfileQr) })
                    } }
                    // The accounts on this phone (ADR 0112, 0135); Nova conta closes the section while the cap allows.
                    if (state.accounts.size > 1) {
                        item { VoceSectionHeader(stringResource(R.string.bipe_app_account_switcher_title)) }
                        item { AccountRows(tabs, media) }
                    }
                    if (state.canAddAccount) item {
                        VoceRow(PhosphorIcon.PLUS, stringResource(R.string.bipe_app_account_new), tint = BipeTheme.colors.accentInk,
                            titleColor = BipeTheme.colors.accentInk, onClick = onNewAccount)
                    }
                    item { VoceSectionHeader(null) }
                    item {
                        VoceRow(PhosphorIcon.NOTEPAD, notesTitle, stringResource(R.string.bipe_self_chat_subtitle),
                            tint = BipeTheme.colors.accentInk, enabled = !openingNotes, onClick = openNotes,
                            trailing = if (openingNotes) { { CircularProgressIndicator(Modifier.size(24.dp), color = BipeTheme.colors.accentFill) } } else null)
                    }
                    item { VoceRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_invites_title), onClick = openInvites) }
                    SettingsRootSection(settings, openGroup)
                }
            }
        })
    LaunchedEffect(tabs) { tabs.refreshProfiles() }
    if (notesFailed) {
        AlertDialog(onDismissRequest = { notesFailed = false },
            text = { Text(stringResource(R.string.bipe_self_chat_error)) },
            confirmButton = { TextButton(onClick = { notesFailed = false; openNotes() }) { Text(stringResource(R.string.bipe_app_retry)) } },
            dismissButton = { TextButton(onClick = { notesFailed = false }) { Text(stringResource(R.string.bipe_app_cancel)) } })
    }
}

@Composable
private fun VoceHeader(account: AccountSummary, media: MediaLoader, openProfile: () -> Unit, openQr: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.bipe_profile_share)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.clickable(onClick = openProfile), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Avatar(account.displayName, account.avatarUrl, media, size = 96.dp, userId = account.userId, isCompany = account.isCompany)
            Text(account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
            account.handle?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        }
        // QR and Compartilhar perfil side by side, as Material 3 tonal buttons.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = openQr, modifier = Modifier.weight(1f)) {
                Icon(painterResource(PhosphorIcon.QR_CODE.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bipe_profile_qr_action))
            }
            account.handle?.let { handle ->
                FilledTonalButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://bipe.chat/@$handle")
                    context.startActivity(Intent.createChooser(send, shareTitle))
                }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(PhosphorIcon.SHARE_NETWORK.drawable), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(shareTitle)
                }
            }
        }
    }
}

@Composable
private fun VoceSectionHeader(title: String?) {
    if (title == null) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Text(title, style = MaterialTheme.typography.labelLarge, color = BipeTheme.colors.accentInk,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp))
}

/** A Material 3 list item with a Phosphor icon, for the rows of Você above Configurações. */
@Composable
private fun VoceRow(
    icon: PhosphorIcon,
    title: String,
    subtitle: String? = null,
    tint: Color = BipeTheme.colors.glyphPrimary,
    titleColor: Color = BipeTheme.colors.inkPrimary,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = BipeTheme.colors
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = subtitle?.let { text -> @Composable { Text(text, color = colors.inkSecondary) } },
        leadingContent = { Icon(painterResource(icon.drawable), contentDescription = null, tint = tint) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = colors.surfaceBase),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}
