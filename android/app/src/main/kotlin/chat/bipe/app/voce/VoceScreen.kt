// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import chat.bipe.shared.generated.BipeOnboardingPrompt
import chat.bipe.shared.onboarding.BipePromptSlot
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import chat.bipe.app.business.BusinessFullScreen
import chat.bipe.app.business.MyBookingsScreen
import chat.bipe.app.common.Avatar
import chat.bipe.app.people.PeopleService
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.app.tabs.AccountRows
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsHost
import chat.bipe.app.linkcomputer.LinkComputerModel
import chat.bipe.app.linkcomputer.LinkComputerScreen
import chat.bipe.app.settings.SettingsRoute
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
    val context = LocalContext.current
    var openingNotes by remember { mutableStateOf(false) }
    var notesFailed by remember { mutableStateOf(false) }
    var showsLinkPhone by rememberSaveable { mutableStateOf(false) }
    var showsMyBookings by rememberSaveable { mutableStateOf(false) }
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
    // Spec voce photoPrompt (bipe-chat/bipe#200).
    val photoPrefs = remember { context.getSharedPreferences("bipe_photo_prompt", Context.MODE_PRIVATE) }
    val photoKey = "dismissed_${session.userId}"
    var photoPromptDismissed by remember(session) { mutableStateOf(photoPrefs.getBoolean(photoKey, false)) }
    val photoSlot = remember(session) { BipePromptSlot(context, session.userId) }
    val trust = remember(session) { TrustCardModel(session, api, scope) }
    val current = state.accounts.firstOrNull { it.isCurrent }
    LaunchedEffect(trust, current) { trust.update(current) }
    val trustProfile by trust.profile.collectAsState()
    SettingsHost(session, api, invites = tabs.invites(session), onClose = { tabs.selectTab(AppTabsModel.Tab.CONVERSAS) }, onSignedOut = { tabs.start.completeSignOut(session.userId) },
        onOpenChat = onOpenChat, onProfileChanged = tabs::refreshProfiles, trust = trust,
        rootContent = { settings, openGroup, openPeople, openInvites, openRoute ->
            // Spec voce openLinkPhone: the only way into link-phone-show (ADR 0059).
            if (showsLinkPhone) {
                LinkComputerScreen(settings, onClose = { showsLinkPhone = false }, mode = LinkComputerModel.Mode.PHONE)
            } else Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.VOCE.id), color = BipeTheme.colors.surfaceBase) {
                LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    item { Text(stringResource(R.string.bipe_tab_you), style = MaterialTheme.typography.headlineMedium,
                        color = BipeTheme.colors.inkPrimary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
                    item { state.accounts.firstOrNull { it.isCurrent }?.let {
                        VoceHeader(it, media, openProfile = { openGroup(BipeSettingsGroup.PROFILE) },
                            openQr = { openPeople(PeopleRoute.ProfileQr) })
                    } }
                    // Spec voce photoPrompt (bipe-chat/bipe#200): one card while the account has no photo.
                    val me = state.accounts.firstOrNull { it.isCurrent }
                    if (me != null && me.isLoaded && me.avatarUrl == null && !photoPromptDismissed &&
                        photoSlot.allows(BipeOnboardingPrompt.PHOTO)) item(key = "photo-prompt") {
                        PhotoPromptCard(open = { openPeople(PeopleRoute.ProfileEdit) }, dismiss = {
                            photoPrefs.edit().putBoolean(photoKey, true).apply()
                            photoPromptDismissed = true
                        })
                        LaunchedEffect(Unit) { photoSlot.didShow(BipeOnboardingPrompt.PHOTO) }
                    }
                    // The accounts on this phone (ADR 0112, 0135); Nova conta closes the section (spec account-new).
                    if (state.accounts.size > 1) {
                        item { VoceSectionHeader(stringResource(R.string.bipe_app_account_switcher_title)) }
                        item { AccountRows(tabs, media) }
                    }
                    item {
                        VoceRow(PhosphorIcon.PLUS, stringResource(R.string.bipe_app_account_new), tint = BipeTheme.colors.accentInk,
                            titleColor = BipeTheme.colors.accentInk, onClick = onNewAccount)
                    }
                    item { VoceSectionHeader(null) }
                    item {
                        VoceRow(PhosphorIcon.NOTEPAD, notesTitle, stringResource(R.string.bipe_self_chat_subtitle),
                            tint = BipeTheme.colors.accentInk, enabled = !openingNotes, onClick = openNotes,
                            trailing = if (openingNotes) { { CircularProgressIndicator(Modifier.size(24.dp), color = BipeTheme.colors.accentFill) } } else null)
                    }
                    // Perfil de confiança after Anotações (ADR 0112, spec trust-card).
                    trustProfile?.let { profile ->
                        item(key = "trust-gap") { VoceSectionHeader(null) }
                        TrustCardSection(profile, TrustActions(
                            addName = { openPeople(PeopleRoute.ProfileEdit) },
                            addPhoto = { openPeople(PeopleRoute.ProfileEdit) },
                            chooseHandle = { openRoute(SettingsRoute.HandlePick) },
                        ), openLadder = { openRoute(SettingsRoute.TrustProfile) })
                        item(key = "trust-gap-end") { VoceSectionHeader(null) }
                    }
                    item { VoceRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_invites_title), onClick = openInvites) }
                    item { VoceRow(PhosphorIcon.DEVICES, stringResource(R.string.bipe_app_link_phone_action), onClick = { showsLinkPhone = true }) }
                    SettingsRootSection(settings, openGroup)
                    // Spec settings-root: Meus agendamentos after the groups, for a personal account.
                    if (current != null && !current.isCompany) {
                        item(key = "my-bookings") {
                            VoceRow(PhosphorIcon.CLOCK, stringResource(R.string.bipe_agenda_my_bookings), onClick = { showsMyBookings = true })
                        }
                    }
                }
            }
        })
    LaunchedEffect(tabs) { tabs.refreshProfiles() }
    if (showsMyBookings) {
        val people = remember(session) { PeopleService(context, session, api, media, scope) }
        BusinessFullScreen(onDismiss = { showsMyBookings = false }) {
            MyBookingsScreen(session, onClose = { showsMyBookings = false }, onOpenChat = { businessId ->
                // Spec agenda-my-bookings openChat: the DM with the business, started when there is none.
                scope.launch {
                    runCatching { people.openOrCreateDm(businessId) }.onSuccess { roomId ->
                        showsMyBookings = false
                        onOpenChat(roomId)
                    }
                }
            })
        }
    }
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

/** Prompt photo of registries/onboarding.json (trigger voceWithoutPhoto, bipe-chat/bipe#200). */
@Composable
private fun PhotoPromptCard(open: () -> Unit, dismiss: () -> Unit) {
    val colors = BipeTheme.colors
    val prompt = BipeOnboardingPrompt.PHOTO
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(colors.accentWash, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(prompt.title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
        prompt.body?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(prompt.primary.title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.inkOnAccent,
                modifier = Modifier.background(colors.accentFill, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = open)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            prompt.text.forEach {
                Text(
                    stringResource(it.title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accentInk,
                    modifier = Modifier.clickable(role = Role.Button, onClick = dismiss),
                )
            }
        }
    }
}
