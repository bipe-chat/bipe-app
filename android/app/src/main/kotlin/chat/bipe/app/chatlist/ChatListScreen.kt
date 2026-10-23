// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.TermsReacceptModel
import chat.bipe.app.account.TermsReacceptScreen
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.people.PeopleLinks
import chat.bipe.app.people.PeopleMenu
import chat.bipe.app.people.PeopleNavigation
import chat.bipe.app.people.PeopleRoute
import chat.bipe.app.people.PeopleScreen
import chat.bipe.app.people.PeopleService
import chat.bipe.app.encryption.EncryptionSheet
import chat.bipe.app.encryption.EncryptionStatusModel
import chat.bipe.app.encryption.HistoryBanner
import chat.bipe.app.groups.CreateGroupButton
import chat.bipe.app.groups.DmSwipeRow
import chat.bipe.app.groups.GroupHost
import chat.bipe.app.groups.withoutClearedDms
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.handle.HandlePickScreen
import chat.bipe.app.handle.HandlePromptCard
import chat.bipe.app.recovery.RecoveryAlertModel
import chat.bipe.app.recovery.RecoveryAlertScreen
import chat.bipe.app.signup.SignupFlowModel
import chat.bipe.app.signup.SignupFlowScreen
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.tabs.AppTabsScreen
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.app.groups.Person as GroupPerson
import chat.bipe.app.people.Person
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.bipeHaptic
import androidx.compose.ui.platform.LocalView
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The signed-in app: app-tabs, chat-timeline on top of it when a chat is open, handle-pick from the prompt card, and
 * terms-reaccept over everything when the legal version changed.
 */
@Composable
fun HomeScreen(
    session: AccountSession,
    tabs: AppTabsModel,
    api: BipeApiClient,
    initialRoomId: String?,
    foregroundCount: Int,
    onSignOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = remember(session) { tabs.chatList(session) }
    val media = remember(session) { tabs.media(session) }
    val handle = remember(session) { HandlePickModel(context, api, session, scope) }
    val terms = remember(session) { TermsReacceptModel(api, session, scope) }
    val requests = remember(session) { ChatRequestsModel(context.applicationContext, session, api, scope) }
    val search = remember(session) { ChatSearchModel(session, model, scope) }
    val remoteBanner = remember(session) { RemoteBannerModel(context.applicationContext, api, scope) }
    var showsRequests by rememberSaveable { mutableStateOf(false) }
    val encryption = remember(session) { EncryptionStatusModel(session, scope, context.applicationContext) }
    val recoveryAlert = remember(session) { RecoveryAlertModel(context.applicationContext, api, session, scope) }
    val alertNotice by recoveryAlert.notice.collectAsState()
    var openRoomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var showsHandlePick by rememberSaveable { mutableStateOf(false) }
    var showsNewAccount by rememberSaveable { mutableStateOf(false) }
    val termsPhase by terms.phase.collectAsState()
    DisposableEffect(requests) {
        handle.load()
        requests.start()
        onDispose { requests.close(); search.dispose() }
    }
    val app = context.applicationContext as BipeApplication
    DisposableEffect(encryption) {
        encryption.observe()
        onDispose { encryption.close() }
    }
    // terms-reaccept, action check: after sign-in and on every return to the foreground.
    LaunchedEffect(foregroundCount) { terms.check(); tabs.refreshProfiles(); requests.refreshTrust() }
    val people = remember(session) { PeopleService(context, session, api, media, scope) }
    var peopleStack by remember { mutableStateOf(listOf<PeopleRoute>()) }
    DisposableEffect(people) {
        people.start()
        onDispose { people.close() }
    }
    // A profile link from the system (spec profile-link-open) opens once the home screen shows.
    val pendingLink by PeopleLinks.pending.collectAsState()
    LaunchedEffect(pendingLink) {
        PeopleLinks.take()?.let { peopleStack = peopleStack + it }
    }
    val navigation = PeopleNavigation(
        push = { peopleStack = peopleStack + it },
        pop = { peopleStack = peopleStack.dropLast(1) },
        openChat = { id ->
            peopleStack = emptyList()
            openRoomId = id
        },
        pickHandle = { showsHandlePick = true },
    )
    // recovery-alert, action check: on launch and on every return to the foreground.
    LaunchedEffect(foregroundCount) { recoveryAlert.check() }
    // Critical banners refresh alongside account/profile and recovery checks.
    LaunchedEffect(foregroundCount) { remoteBanner.refresh() }
    // Spec notification-service, action open.
    val roomToOpen by app.roomToOpen.collectAsState()
    LaunchedEffect(roomToOpen) {
        roomToOpen?.let {
            peopleStack = emptyList()
            showsHandlePick = false
            showsRequests = false
            openRoomId = it
            app.roomToOpen.value = null
        }
    }
    // An open chat has no notifications, and its pushes show none.
    DisposableEffect(openRoomId) {
        app.notifications.visibleRoomId = openRoomId
        openRoomId?.let { app.notifications.clear(it) }
        onDispose { app.notifications.visibleRoomId = null }
    }
    val roomId = openRoomId
    when {
        termsPhase != TermsReacceptModel.Phase.HIDDEN -> TermsReacceptScreen(terms, onSignOut)
        alertNotice != null -> RecoveryAlertScreen(recoveryAlert)
        showsNewAccount -> {
            BackHandler { showsNewAccount = false }
            val signup = remember(session) {
                SignupFlowModel(context, api, tabs.start.vault, tabs.start.sessionStore, scope, onSignedUp = { created, outcome ->
                    showsNewAccount = false
                    tabs.start.didSignUp(created, outcome)
                })
            }
            SignupFlowScreen(signup, onCancel = { showsNewAccount = false })
        }
        showsHandlePick -> {
            BackHandler { showsHandlePick = false }
            HandlePickScreen(handle, onClose = { showsHandlePick = false })
        }
        showsRequests -> ChatRequestsScreen(requests, media,
            onBack = { showsRequests = false },
            onOpenChat = { showsRequests = false; openRoomId = it },
            onOpenWhoCanMessage = { showsRequests = false; navigation.push(PeopleRoute.WhoCanMessage) })
        else -> GroupHost(session, api, media, roomId, foregroundCount, onOpenRoom = {
            peopleStack = emptyList()
            openRoomId = it
        }) { groups ->
            if (peopleStack.isNotEmpty()) {
                BackHandler { navigation.pop() }
                // The people screens reach group-create, chat-notifications, dm-delete and invites through GroupHost.
                val withGroups = PeopleNavigation(
                    push = navigation.push,
                    pop = navigation.pop,
                    openChat = navigation.openChat,
                    pickHandle = navigation.pickHandle,
                    newGroup = { userId ->
                        scope.launch {
                            val person = runCatching { people.person(userId) }.getOrNull()
                            groups.createGroupWith(
                                listOf(GroupPerson(userId, person?.displayName ?: Person.localpart(userId), person?.avatarUrl, person?.handle)),
                            )
                        }
                    },
                    notifications = groups.openNotifications,
                    deleteChat = groups.deleteDm,
                    invite = groups.openInvites,
                )
                PeopleScreen(peopleStack.last(), people, withGroups)
            } else if (roomId != null) {
                BackHandler { openRoomId = null }
                ChatTimelineScreen(
                    session,
                    media,
                    roomId,
                    onBack = { openRoomId = null },
                    onHistoryHelp = encryption::openSheet,
                    onOpenGroupDetails = groups.openGroupDetails,
                    onOpenInfo = {
                        scope.launch {
                            people.dmPartner(roomId)?.let { navigation.push(PeopleRoute.PersonProfile(it, fromDm = true)) }
                        }
                    },
                )
            } else {
                AppTabsScreen(
                    tabs = tabs, chatList = model, media = media, handle = handle,
                    onOpenHandlePick = { showsHandlePick = true },
                    onOpenChat = { openRoomId = it },
                    onNewAccount = { if (tabs.state.value.canAddAccount) showsNewAccount = true },
                    banner = { HistoryBanner(encryption) },
                    onCreateGroup = groups.createGroup,
                    onDeleteDm = groups.deleteDm,
                    requests = requests, search = search, remoteBanner = remoteBanner,
                    onOpenRequests = { showsRequests = true },
                    onFindHandle = { navigation.push(PeopleRoute.ProfileLinkOpen(it.trim().removePrefix("@"))) },
                    onInvite = groups.openInvites,
                    onOpenPeople = navigation.push,
                )
            }
        }
    }
    EncryptionSheet(encryption)
}

/** Spec chat-list, one scope: Conversas or Grupos (ADR 0053). Search is an action in the top bar (spec app-tabs). */
@Composable
fun ChatListScreen(
    model: ChatListModel,
    scope: ChatScope,
    media: MediaLoader,
    handle: HandlePickModel,
    onOpenSearch: () -> Unit,
    onOpenHandlePick: () -> Unit,
    onOpenChat: (String) -> Unit,
    requests: ChatRequestsModel? = null,
    remoteBanner: RemoteBannerModel? = null,
    onOpenRequests: () -> Unit = {},
    account: AccountSummary? = null,
    onOpenVoce: () -> Unit = {},
    /** Spec encryption-status: the banner above the chat list. */
    banner: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
    onInvite: () -> Unit = {},
    onOpenPeople: (PeopleRoute) -> Unit = {},
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val unreadScopes by model.unreadScopes.collectAsState()
    val unreadChats by model.unreadChats.collectAsState()
    val unreadLoading by model.unreadLoading.collectAsState()
    val unreadOnly = scope in unreadScopes
    val allPhase by model.phase.collectAsState()
    val allChats by model.chats.collectAsState()
    val displayed = if (unreadOnly) unreadChats[scope].orEmpty() else allChats
    val visibleChats = withoutClearedDms(displayed, { it.roomId }, { it.lastActivity })
    val chats = visibleChats.filter { it.scope == scope }
    val phase = if (unreadOnly && scope in unreadLoading) ChatListModel.Phase.LOADING else model.phase(scope, allPhase, visibleChats)
    val handleState by handle.state.collectAsState()
    val requestList = requests?.requests?.collectAsState()?.value.orEmpty()
    val groupInvites = requests?.groupInvites?.collectAsState()?.value.orEmpty()
    val pending = requests?.pending?.collectAsState()?.value.orEmpty()
    val requestsError = requests?.error?.collectAsState()?.value == true
    val rowCount = requestList.count { it.roomId !in pending }
    val invites = groupInvites.filter { it.roomId !in pending }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (scope == ChatScope.CONVERSAS) R.string.bipe_app_chat_list_title else R.string.bipe_tab_groups),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                CreateGroupButton(onCreateGroup, onInvite)
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        painterResource(PhosphorIcon.MAGNIFYING_GLASS.drawable),
                        contentDescription = stringResource(R.string.bipe_app_tab_search),
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
                if (account != null) {
                    val label = stringResource(R.string.bipe_app_account_current_a11y, account.displayName.ifBlank { stringResource(R.string.bipe_tab_you) })
                    IconButton(onClick = onOpenVoce, modifier = Modifier.semantics { contentDescription = label }) {
                        Avatar(account.displayName, account.avatarUrl, media, size = 32.dp, userId = account.userId, isCompany = account.isCompany)
                    }
                }
            }
            remoteBanner?.let { RemoteBannerCard(it, context) }
            // Spec encryption-status: the banner above the chat list.
            banner()
            run {
                if (handleState.showsPrompt) HandlePromptCard(handle, open = onOpenHandlePick)
                UnreadChip(unreadOnly) { model.toggleUnread(scope) }
                when (phase) {
                    ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentFill)
                    }
                    ChatListModel.Phase.EMPTY, ChatListModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // The requests row and the group invites (specs chat-requests and chat-list-filters).
                        if (scope == ChatScope.CONVERSAS && rowCount > 0) item(key = "requests") { ChatRequestsRow(rowCount, onOpenRequests) }
                        if (scope == ChatScope.GRUPOS && requests != null) {
                            items(invites, key = { "invite-${it.roomId}" }) { invite ->
                                GroupInviteRow(requests, invite, media, onOpenChat)
                            }
                        }
                        if (requestsError) item(key = "requests-error") {
                            Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
                        }
                        if (phase == ChatListModel.Phase.EMPTY) item(key = "empty") { EmptyState(scope, unreadOnly) { model.toggleUnread(scope) } }
                        items(chats, key = { it.roomId }) { chat ->
                            DmSwipeRow(chat.isDm, onDelete = { onDeleteDm(chat.roomId) }) { ChatRow(chat, media) { onOpenChat(chat.roomId) } }
                            if (chat.roomId == chats.lastOrNull()?.roomId) {
                                LaunchedEffect(chats.size) { model.loadMore(scope) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatRow(chat: ChatSummary, media: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, chat.avatarUrl, media, userId = chat.peerUserId)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                chat.lastActivity?.let { time ->
                    Text(
                        timeLabel(context, time),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Spec chat-notifications: a muted chat keeps its badge, without sound or notification.
                if (chat.isMuted) {
                    Icon(
                        painterResource(PhosphorIcon.BELL_SLASH.drawable),
                        contentDescription = stringResource(R.string.bipe_app_notification_mode_off),
                        tint = colors.glyphSecondary,
                        modifier = Modifier.padding(start = 8.dp).size(16.dp),
                    )
                }
                if (chat.unreadCount > 0) {
                    val count = NumberFormat.getIntegerInstance().format(chat.unreadCount)
                    val label = stringResource(R.string.bipe_app_chat_list_unread, count)
                    Text(
                        count,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkOnAccent,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .background(colors.accentFill, CircleShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                            .semantics { contentDescription = label },
                    )
                }
            }
        }
    }
}

private fun timeLabel(context: android.content.Context, time: Long): String =
    if (DateUtils.isToday(time)) {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)
    } else {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    }

/** The only filter chip: Unread (spec chat-list-filters). */
@Composable
private fun UnreadChip(isOn: Boolean, onToggle: () -> Unit) {
    val colors = BipeTheme.colors
    val view = LocalView.current
    Text(
        stringResource(R.string.bipe_app_chat_list_filter_unread),
        style = MaterialTheme.typography.labelLarge,
        color = if (isOn) colors.inkOnAccent else colors.inkPrimary,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(if (isOn) colors.accentFill else colors.surfaceRaised, CircleShape)
            .clickable {
                onToggle()
                view.bipeHaptic(BipeHaptic.Tick)
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** The empty messages of chat-list-filters: noUnread turns the filter off on a tap; noChats offers start chat. */
@Composable
private fun EmptyState(scope: ChatScope, unreadOnly: Boolean, onTurnOffUnread: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (unreadOnly) {
            Text(
                stringResource(R.string.bipe_app_chat_list_no_unread),
                color = colors.inkSecondary,
                modifier = Modifier.clickable(onClick = onTurnOffUnread),
            )
        } else if (scope == ChatScope.GRUPOS) {
            Text(stringResource(R.string.bipe_groups_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_groups_empty_body), color = colors.inkSecondary)
        } else {
            Text(stringResource(R.string.bipe_chats_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_chats_empty_body), color = colors.inkSecondary)
            // Action startChat: the start-chat screen comes with people and profiles (bipe-chat/bipe#568).
            Text(stringResource(R.string.bipe_action_start_chat), color = colors.inkDisabled)
        }
    }
}
