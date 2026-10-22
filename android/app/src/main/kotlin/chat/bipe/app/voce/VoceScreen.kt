// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

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
                        color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(16.dp)) }
                    item { state.accounts.firstOrNull { it.isCurrent }?.let {
                        VoceHeader(it, media, openProfile = { openGroup(BipeSettingsGroup.PROFILE) },
                            openQr = { openPeople(PeopleRoute.ProfileQr) })
                    } }
                    if (state.accounts.size > 1) {
                        item { Text(stringResource(R.string.bipe_app_account_switcher_title), color = BipeTheme.colors.inkSecondary, modifier = Modifier.padding(16.dp)) }
                        item { AccountRows(tabs, media) }
                    }
                    if (state.canAddAccount) item {
                        TextButton(onClick = onNewAccount, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.bipe_app_account_new))
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !openingNotes, onClick = openNotes).padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(PhosphorIcon.NOTEPAD.drawable), contentDescription = null, tint = BipeTheme.colors.glyphPrimary)
                            Column(Modifier.weight(1f)) {
                                Text(notesTitle, color = BipeTheme.colors.inkPrimary, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.bipe_self_chat_subtitle), color = BipeTheme.colors.inkSecondary)
                            }
                            if (openingNotes) CircularProgressIndicator(Modifier.size(24.dp), color = BipeTheme.colors.accentFill)
                        }
                    }
                    item {
                        TextButton(onClick = openInvites, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Icon(painterResource(PhosphorIcon.USER_PLUS.drawable), contentDescription = null)
                            Text(stringResource(R.string.bipe_invites_title))
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.clickable(onClick = openProfile), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(account.displayName, account.avatarUrl, media, size = 88.dp, userId = account.userId, isCompany = account.isCompany)
            Text(account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) }, style = MaterialTheme.typography.headlineSmall, color = colors.inkPrimary)
        }
        account.handle?.let { handle ->
            Text("@$handle", style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://bipe.chat/@$handle")
                context.startActivity(Intent.createChooser(send, shareTitle))
            }) { Text(shareTitle) }
        }
        TextButton(onClick = openQr) { Text(stringResource(R.string.bipe_profile_qr_action)) }
    }
}
