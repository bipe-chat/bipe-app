// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.voce

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size

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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.business.BusinessFullScreen
import chat.bipe.app.business.MyBookingsScreen
import chat.bipe.app.people.PeopleService
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.account.AccountSession
import chat.bipe.app.settings.SettingsHost
import chat.bipe.app.settings.SettingsRoute
import chat.bipe.app.settings.SettingsRootSection
import chat.bipe.app.people.PeopleRoute
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
    val trust = remember(session) { TrustCardModel(session, api, scope) }
    val current = state.accounts.firstOrNull { it.isCurrent }
    LaunchedEffect(trust, current) { trust.update(current) }
    val trustProfile by trust.profile.collectAsState()
    SettingsHost(session, api, invites = tabs.invites(session), onClose = { tabs.selectTab(AppTabsModel.Tab.CONVERSAS) }, onSignedOut = { tabs.start.completeSignOut(session.userId) },
        onOpenChat = onOpenChat, onProfileChanged = tabs::refreshProfiles, trust = trust,
        // Spec year-recap: the last row of Conversas e mídia opens it.
        yearRecap = { back ->
            BusinessFullScreen(onDismiss = back) { YearRecapScreen(session, tabs.chatList(session), onBack = back) }
        },
        rootContent = { settings, openGroup, openPeople, openInvites, openRoute ->
            Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.VOCE.id), color = BipeTheme.colors.surfaceBase) {
                LazyColumn(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                    // Spec voce openQr: the QR code button at the end of the title row; share and copy live on that
                    // screen (profile-link).
                    item {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.bipe_tab_you), style = MaterialTheme.typography.headlineMedium,
                                color = BipeTheme.colors.inkPrimary, modifier = Modifier.weight(1f))
                            IconButton(onClick = { openPeople(PeopleRoute.ProfileQr) }) {
                                Icon(painterResource(PhosphorIcon.QR_CODE.drawable), contentDescription = stringResource(R.string.bipe_profile_qr_action),
                                    tint = BipeTheme.colors.glyphPrimary)
                            }
                        }
                    }
                    // Spec voce (bipe-chat/bipe#988): the profile row, then the trust card. No prompt cards here
                    // (bipe-chat/bipe#894): chat-list-prompt shows every ask.
                    current?.let { account ->
                        item(key = "profile") { VoceProfileRow(account, media, openProfile = { openPeople(PeopleRoute.ProfileEdit) }) }
                    }
                    trustProfile?.let { profile ->
                        item(key = "trust-gap") { VoceSectionHeader(null) }
                        TrustCardSection(profile, TrustActions(
                            addName = { openPeople(PeopleRoute.ProfileEdit) },
                            addPhoto = { openPeople(PeopleRoute.ProfileEdit) },
                            chooseHandle = { openRoute(SettingsRoute.HandlePick) },
                        ), openLadder = { openRoute(SettingsRoute.TrustProfile) })
                    }
                    item { VoceSectionHeader(null) }
                    item {
                        VoceRow(PhosphorIcon.NOTEPAD, notesTitle, stringResource(R.string.bipe_self_chat_subtitle),
                            enabled = !openingNotes, onClick = openNotes,
                            trailing = if (openingNotes) { { CircularProgressIndicator(Modifier.size(24.dp), color = BipeTheme.colors.accentFill) } } else null)
                    }
                    // Spec voce: Meus agendamentos after Anotações, for a personal account.
                    if (current != null && !current.isCompany) {
                        item(key = "my-bookings") {
                            VoceRow(PhosphorIcon.CLOCK, stringResource(R.string.bipe_agenda_my_bookings), onClick = { showsMyBookings = true })
                        }
                    }
                    item { VoceSectionHeader(null) }
                    item { VoceRow(PhosphorIcon.USER_PLUS, stringResource(R.string.bipe_invites_title), onClick = openInvites) }
                    SettingsRootSection(settings, openGroup)
                    // Spec voce (MZ, 4 Nov 2026): the accounts stay in the switcher, which this row opens too, as the Você
                    // tab does (hold, TalkBack action). Adicionar conta is the last row (spec account-new).
                    item { VoceSectionHeader(null) }
                    item {
                        VoceRow(PhosphorIcon.USERS_THREE, stringResource(R.string.bipe_app_account_switcher_title), onClick = tabs::openAccountSwitcher)
                    }
                    item { VoceRow(PhosphorIcon.PLUS, stringResource(R.string.bipe_app_account_new), onClick = onNewAccount) }
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
