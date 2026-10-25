// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import chat.bipe.app.common.OfficialBadge
import chat.bipe.app.encryption.EncryptionSheet
import chat.bipe.app.encryption.EncryptionStatusModel
import chat.bipe.app.encryption.HistoryBanner
import chat.bipe.app.groups.CreateGroupButton
import chat.bipe.app.groups.GroupHost
import chat.bipe.app.groups.Person as GroupPerson
import chat.bipe.app.groups.withoutClearedDms
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.handle.HandlePickScreen
import chat.bipe.app.handle.HandlePromptCard
import chat.bipe.app.people.PeopleLinks
import chat.bipe.app.people.PeopleMenu
import chat.bipe.app.people.PeopleNavigation
import chat.bipe.app.people.PeopleRoute
import chat.bipe.app.people.PeopleScreen
import chat.bipe.app.people.LocalBipeBrandMarks
import chat.bipe.app.people.BrandMarkCaption
import chat.bipe.app.people.PeopleService
import chat.bipe.app.people.Person
import chat.bipe.app.pixcheck.PixPreview
import chat.bipe.app.pixcheck.PixSendScreen
import chat.bipe.app.recovery.RecoveryAlertModel
import chat.bipe.app.recovery.RecoveryAlertScreen
import chat.bipe.app.settings.PhoneConfirmNoticeDialog
import chat.bipe.app.settings.PhoneConfirmNoticeModel
import chat.bipe.app.recovery.RecoveryEmailAddScreen
import chat.bipe.app.recovery.RecoveryReminderCard
import chat.bipe.app.recovery.RecoveryReminderModel
import chat.bipe.app.settings.PinChangeScreen
import chat.bipe.app.settings.SettingsModel
import chat.bipe.app.signup.SignupStart
import chat.bipe.app.tabs.AccountNewScreen
import chat.bipe.app.tabs.InviteSignedInScreen
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.tabs.AppTabsScreen
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.BipeNotificationTarget
import chat.bipe.shared.BipeNotificationDestination
import chat.bipe.shared.BipeProfileLink
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeOnboardingTarget
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.launch
import java.text.NumberFormat

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
    val recovery = remember(session) { RecoveryReminderModel(context.applicationContext, api, session, scope) }
    val recoveryState by recovery.state.collectAsState()
    var showsRecoveryEmail by rememberSaveable { mutableStateOf(false) }
    // Spec recovery-reminder syncOff and spec pin-reminder (bipe-chat/bipe#202).
    var syncOff by remember(session) { mutableStateOf(false) }
    val pinReminder = remember(session) { PinReminderModel(context.applicationContext, session.userId) }
    val showsPinCard by pinReminder.showsCard.collectAsState()
    var showsPinReminder by rememberSaveable { mutableStateOf(false) }
    var showsPinChange by rememberSaveable { mutableStateOf(false) }
    val settings = remember(session) { SettingsModel(context.applicationContext, session, api, scope) }
    val terms = remember(session) { TermsReacceptModel(api, session, scope) }
    val people = remember(session) { PeopleService(context, session, api, media, scope) }
    val requests = remember(session) { ChatRequestsModel(context.applicationContext, session, api, scope, people) }
    val search = remember(session) { ChatSearchModel(session, model, scope) }
    val remoteBanner = remember(session) { RemoteBannerModel(context.applicationContext, api, scope) }
    var showsRequests by rememberSaveable { mutableStateOf(false) }
    var notificationListOpen by rememberSaveable { mutableStateOf(0) }
    val encryption = remember(session) { EncryptionStatusModel(session, scope, context.applicationContext) }
    val recoveryAlert = remember(session) { RecoveryAlertModel(context.applicationContext, api, session, scope) }
    val alertNotice by recoveryAlert.notice.collectAsState()
    val phoneNotice = remember(session) { PhoneConfirmNoticeModel(context.applicationContext, api, session, scope) }
    var openRoomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var showsHandlePick by rememberSaveable { mutableStateOf(false) }
    var showsNewAccount by rememberSaveable { mutableStateOf(false) }
    // invite-signed-in: a sign-up with someone else's invite from a link.
    var newAccountPreset by remember { mutableStateOf<SignupStart?>(null) }
    val inviteTap by tabs.start.inviteTap.collectAsState()
    val termsPhase by terms.phase.collectAsState()
    DisposableEffect(requests) {
        handle.load()
        requests.start()
        onDispose { requests.close(); search.dispose() }
    }
    // Spec chat-request contactCollision: the joined DMs are contacts (security rule 8).
    LaunchedEffect(requests, model) { model.chats.collect { requests.rememberDmPartners(it) } }
    val app = context.applicationContext as BipeApplication
    DisposableEffect(encryption) {
        encryption.observe()
        onDispose { encryption.close() }
    }
    // terms-reaccept, action check: after sign-in and on every return to the foreground.
    LaunchedEffect(foregroundCount) { terms.check(); tabs.refreshProfiles(); requests.refreshTrust() }
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
    // phone-link-status, action checkNotice: on launch and on every return to the foreground.
    LaunchedEffect(foregroundCount) { phoneNotice.check() }
    PhoneConfirmNoticeDialog(phoneNotice)
    // Critical banners refresh alongside account/profile and recovery checks.
    LaunchedEffect(foregroundCount) { remoteBanner.refresh() }
    LaunchedEffect(foregroundCount) { recovery.load() }
    LaunchedEffect(foregroundCount) {
        pinReminder.refresh()
        syncOff = tabs.start.recoveryTokens.isAvailable && !tabs.start.recoveryTokens.isBackedUp()
    }
    // Spec notification-service, action open.
    val roomToOpen by app.roomToOpen.collectAsState()
    // Spec pix-check-result, action sendToChat: a preview from Conferir no Bipe, for this account only.
    val pixPreview by app.pixPreview.collectAsState()
    val pendingPix = remember(pixPreview) { pixPreview?.let { PixPreview.resolve(context, it) } }
    LaunchedEffect(Unit) { PixPreview.deleteStale(context) }
    LaunchedEffect(roomToOpen) {
        roomToOpen?.takeIf { it.accountUserId == session.userId }?.let {
            var opensChat = false
            if (it.destination == BipeNotificationDestination.CHAT) {
                opensChat = runCatching {
                    val info = session.client.getRoom(it.roomId)?.roomInfo()
                    info != null && info.membership == org.matrix.rustcomponents.sdk.Membership.JOINED && !info.isSpace
                }.getOrDefault(false)
            }
            if (app.accountStart.sessions.value.none { active -> active === session } || app.roomToOpen.value != it) return@LaunchedEffect
            // The current owner can consume this before the parent route effect; select its destination here too.
            tabs.selectTab(if (it.destination == BipeNotificationDestination.GROUPS) AppTabsModel.Tab.GRUPOS else AppTabsModel.Tab.CONVERSAS)
            peopleStack = emptyList()
            showsHandlePick = false
            showsRequests = it.destination == BipeNotificationDestination.REQUESTS
            notificationListOpen += 1
            openRoomId = it.roomId.takeIf { opensChat }
            app.roomToOpen.value = null
        }
    }
    // An open chat has no notifications, and its pushes show none.
    DisposableEffect(session, openRoomId) {
        val target = BipeNotificationTarget.create(session.userId, openRoomId)
        app.notifications.visibleRoom = target
        target?.let { app.notifications.clear(it) }
        onDispose { if (app.notifications.visibleRoom == target) app.notifications.visibleRoom = null }
    }
    val roomId = openRoomId
    // Spec brand-mark: chat list rows read the session's brand marks.
    CompositionLocalProvider(LocalBipeBrandMarks provides people.brandMarks) {
        when {
            termsPhase != TermsReacceptModel.Phase.HIDDEN -> TermsReacceptScreen(terms, onSignOut)
            pendingPix != null -> PixSendScreen(pendingPix, model, media) {
                pendingPix.delete()
                app.pixPreview.value = null
            }
            alertNotice != null -> RecoveryAlertScreen(recoveryAlert)
            showsNewAccount -> AccountNewScreen(tabs, session, api, onClose = { showsNewAccount = false; newAccountPreset = null },
                preset = newAccountPreset)
            inviteTap != null -> InviteSignedInScreen(
                inviteTap!!, session, api, tabs.state.value.accounts.map { it.userId },
                onClose = tabs.start::inviteTapShown,
                openChat = { roomId ->
                    tabs.start.inviteTapShown()
                    tabs.selectTab(AppTabsModel.Tab.CONVERSAS)
                    peopleStack = emptyList()
                    openRoomId = roomId
                },
                openProfile = { handle ->
                    tabs.start.inviteTapShown()
                    tabs.selectTab(AppTabsModel.Tab.CONVERSAS)
                    navigation.push(PeopleRoute.ProfileLinkOpen(handle))
                },
                newAccount = { start ->
                    tabs.start.inviteTapShown()
                    newAccountPreset = start
                    showsNewAccount = true
                },
            )
            showsHandlePick -> {
                BackHandler { showsHandlePick = false }
                HandlePickScreen(handle, onClose = { showsHandlePick = false })
            }
            showsPinReminder -> {
                BackHandler { showsPinReminder = false }
                PinReminderScreen(session, api, onResult = pinReminder::checked,
                    onChangePin = { showsPinReminder = false; showsPinChange = true }, onClose = { showsPinReminder = false })
            }
            showsPinChange -> {
                BackHandler { showsPinChange = false }
                PinChangeScreen(settings, onClose = { showsPinChange = false })
            }
            showsRecoveryEmail -> RecoveryEmailAddScreen(
                api, session, current = recoveryState.account?.email, pending = recoveryState.account?.emailPending,
                onSent = recovery::load, onClose = { showsRecoveryEmail = false },
            )
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
                        people = people,
                        onOpenChat = { openRoomId = it },
                    )
                } else {
                    AppTabsScreen(
                        tabs = tabs, chatList = model, media = media, handle = handle,
                        listOpenRequest = notificationListOpen,
                        onOpenHandlePick = { showsHandlePick = true },
                        onOpenChat = { openRoomId = it },
                        onNewAccount = { showsNewAccount = true },
                        banner = { HistoryBanner(encryption) },
                        recoveryCard = {
                        if (recovery.showsCard(recoveryState)) RecoveryReminderCard(recovery, syncOff) { showsRecoveryEmail = true }
                        else if (showsPinCard) PinReminderCard(pinReminder) { showsPinReminder = true }
                        },
                        onCreateGroup = groups.createGroup,
                        onDeleteDm = groups.deleteDm,
                        onChatNotifications = groups.openNotifications,
                        onChatInfo = { chat ->
                            if (chat.isDm) chat.peerUserId?.let { navigation.push(PeopleRoute.PersonProfile(it, fromDm = true)) }
                            else groups.openDetailsForRoom(chat.roomId)
                        },
                        requests = requests, search = search, remoteBanner = remoteBanner,
                        onOpenRequests = { showsRequests = true },
                        onFindHandle = { navigation.push(PeopleRoute.ProfileLinkOpen(it.trim().removePrefix("@"))) },
                        onInvite = groups.openInvites,
                        onOpenPeople = navigation.push,
                    )
                }
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
    /** Spec encryption-status: the banner, the first row of the list so it scrolls with the chats. */
    banner: @Composable () -> Unit = {},
    /** Spec recovery-reminder: shows when the handle card does not (one card at a time). */
    recoveryCard: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
    onChatNotifications: (String) -> Unit = {},
    onChatInfo: (ChatSummary) -> Unit = {},
    onInvite: () -> Unit = {},
    onOpenPeople: (PeopleRoute) -> Unit = {},
    listOpenRequest: Int = 0,
    /** Spec chat-list-cards shareInvite; `null` outside Conversas. */
    api: BipeApiClient? = null,
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val cardScope = rememberCoroutineScope()
    val cards = remember(model.session.userId) { FirstListCardsModel(context, model.session.userId) }
    val doneCards by cards.done.collectAsState()
    val notesTitle = stringResource(R.string.bipe_self_chat_title)
    val allPhase by model.phase.collectAsState()
    val allChats by model.chats.collectAsState()
    val archiveMarkers by model.archive.markers.collectAsState()
    val archiveFailed by model.archive.failed.collectAsState()
    val readFailed by model.readActionFailed.collectAsState()
    val markingRoomId by model.markingRoomId.collectAsState()
    var showingArchived by rememberSaveable(model.session.userId, scope) { mutableStateOf(false) }
    LaunchedEffect(listOpenRequest) { if (listOpenRequest > 0) showingArchived = false }
    val visibleChats = withoutClearedDms(allChats, { it.roomId }, { it.lastActivity })
    val chats = visibleChats.filter { scope.shows(it.scope) && archiveMarkers.containsKey(it.roomId) == showingArchived }
    val phase = model.phase(scope, allPhase, chats)
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
                    stringResource(if (scope == ChatScope.GRUPOS) R.string.bipe_tab_groups else R.string.bipe_app_chat_list_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                CreateGroupButton(onCreateGroup, onInvite)
                // Search apart from the bottom bar destinations, at the end of the top bar (spec app-tabs). No account
                // avatar here: Você shows the current account (MZ decision 25 Oct 2026, spec chat-list).
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        painterResource(PhosphorIcon.MAGNIFYING_GLASS.drawable),
                        contentDescription = stringResource(R.string.bipe_app_tab_search),
                        tint = colors.inkPrimary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            remoteBanner?.let { RemoteBannerCard(it, context) }
            run {
                if (handleState.showsPrompt) HandlePromptCard(handle, open = onOpenHandlePick)
                else if (scope != ChatScope.GRUPOS) recoveryCard()
                TextButton(onClick = { showingArchived = !showingArchived }) {
                    Text(stringResource(if (!showingArchived) R.string.bipe_archived_title else if (scope == ChatScope.GRUPOS) R.string.bipe_tab_groups else R.string.bipe_app_chat_list_title))
                }
                if (readFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bipe_chat_read_error), color = colors.trustDangerInk, modifier = Modifier.weight(1f).padding(16.dp))
                    TextButton(onClick = model::retryReadAction, enabled = markingRoomId == null) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                if (archiveFailed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.bipe_archive_error), color = colors.trustDangerInk, modifier = Modifier.weight(1f).padding(16.dp))
                    TextButton(onClick = { model.archive.retry(allChats) }) { Text(stringResource(R.string.bipe_app_retry)) }
                }
                when (phase) {
                    ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentFill)
                    }
                    ChatListModel.Phase.EMPTY, ChatListModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // Spec encryption-status: the first row, so it scrolls with the chats (MZ decision 25 Oct 2026).
                        if (!showingArchived) item(key = "history-banner") { banner() }
                        // Spec chat-list-cards: the first-list cards at the top of Conversas.
                        if (!showingArchived && scope != ChatScope.GRUPOS) {
                            val count = allChats.count { it.scope == ChatScope.CONVERSAS && !archiveMarkers.containsKey(it.roomId) }
                            items(cards.visible(doneCards, count, handleState.currentHandle), key = { "card-${it.name}" }) { card ->
                                FirstListCard(card, handleState.currentHandle) { target ->
                                    cards.finish(card)
                                    when (target) {
                                        BipeOnboardingTarget.SHARE_INVITE -> cardScope.launch {
                                            val link = api?.let { shareableInvite(it, model.session) }
                                            if (link != null) {
                                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                                    .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bipe_invites_share_message, link)), null))
                                            } else {
                                                onInvite()
                                            }
                                        }
                                        BipeOnboardingTarget.SHARE_PROFILE -> handleState.currentHandle?.let { BipeProfileLink.url(it) }?.let { link ->
                                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                                .putExtra(Intent.EXTRA_TEXT, link), null))
                                        }
                                        BipeOnboardingTarget.PROFILE_QR -> onOpenPeople(PeopleRoute.ProfileQr)
                                        BipeOnboardingTarget.NOTES -> cardScope.launch {
                                            runCatching { model.openNotes(notesTitle) }.getOrNull()?.let(onOpenChat)
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        // The requests row and the group invites (specs chat-requests and chat-list-filters).
                        if (!showingArchived && scope != ChatScope.GRUPOS && rowCount > 0) item(key = "requests") { ChatRequestsRow(rowCount, onOpenRequests) }
                        if (!showingArchived && scope != ChatScope.CONVERSAS && requests != null) {
                            items(invites, key = { "invite-${it.roomId}" }) { invite ->
                                GroupInviteRow(requests, invite, media, onOpenChat)
                            }
                        }
                        if (requestsError) item(key = "requests-error") {
                            Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
                        }
                        if (phase == ChatListModel.Phase.EMPTY) item(key = "empty") {
                            if (showingArchived) Text(stringResource(R.string.bipe_archived_empty), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
                            else EmptyState(scope, onStartChat = { onOpenPeople(PeopleRoute.Contacts) }, onCreateGroup = onCreateGroup)
                        }
                        items(chats, key = { it.roomId }) { chat ->
                            ArchivedChatRow(
                                chat, media, ownerId = model.session.userId,
                                archived = archiveMarkers.containsKey(chat.roomId), readPending = markingRoomId != null,
                                onArchive = { model.currentChat(chat.roomId)?.let { model.archive.set(it, !archiveMarkers.containsKey(it.roomId)) } },
                                onOpen = { onOpenChat(chat.roomId) },
                                onRead = { model.setUnread(chat.roomId, !chat.isUnread) },
                                onNotifications = { if (model.currentChat(chat.roomId) != null) onChatNotifications(chat.roomId) },
                                onInfo = if (!chat.isDm || chat.peerUserId != null) ({ model.currentChat(chat.roomId)?.let(onChatInfo) }) else null,
                                onDelete = if (chat.isDm) ({ if (model.currentChat(chat.roomId)?.isDm == true) onDeleteDm(chat.roomId) }) else null,
                            )
                            if (!showingArchived && chat.roomId == chats.lastOrNull()?.roomId) {
                                LaunchedEffect(chats.size) { model.loadMore(scope) }
                            }
                        }
                        if (!showingArchived) item(key = "load-more") {
                            TextButton(onClick = { model.loadMore(scope) }) { Text(stringResource(R.string.bipe_chat_list_more)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatRow(chat: ChatSummary, media: MediaLoader, onLongClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val menuLabel = stringResource(R.string.bipe_chat_actions)
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = menuLabel.takeIf { onLongClick != null })
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
                    modifier = Modifier.weight(1f, fill = !chat.isOfficial),
                )
                if (chat.isOfficial) {
                    Spacer(Modifier.width(6.dp))
                    OfficialBadge()
                    Spacer(Modifier.weight(1f))
                }
                chat.lastActivity?.let { time ->
                    Text(
                        timeLabel(context, time),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary,
                        maxLines = 1,
                        modifier = Modifier.semantics {
                            contentDescription = java.text.DateFormat.getDateTimeInstance(
                                java.text.DateFormat.FULL, java.text.DateFormat.SHORT,
                                context.resources.configuration.locales[0],
                            ).format(time)
                        },
                    )
                }
            }
            // Spec brand-mark: a reseller's mark under the name.
            BrandMarkCaption(chat.peerUserId)
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
                    if (chat.mentionCount > 0) {
                        // A mention has its own mark, not only a colour (bipe#497). The count's label reads it.
                        Text(
                            "@",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkOnAccent,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(colors.accentFill, CircleShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .clearAndSetSemantics {},
                        )
                    }
                    val count = NumberFormat.getIntegerInstance().format(chat.unreadCount)
                    // A mention is read with the count; the badge shows it by colour only (bipe#495).
                    val unread = stringResource(R.string.bipe_app_chat_list_unread, count)
                    val label = if (chat.mentionCount > 0) "$unread, ${stringResource(R.string.bipe_a11y_mention)}" else unread
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
                } else if (chat.isMarkedUnread) {
                    val unreadLabel = stringResource(R.string.bipe_chat_unread_state)
                    Box(Modifier.size(8.dp).background(colors.accentFill, CircleShape).semantics { contentDescription = unreadLabel })
                }
            }
        }
        actions()
    }
}

private fun timeLabel(context: android.content.Context, time: Long): String =
    if (DateUtils.isToday(time)) {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_TIME)
    } else {
        DateUtils.formatDateTime(context, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    }

/** The empty messages of chat-list-filters: noChats offers start chat. */
@Composable
private fun EmptyState(scope: ChatScope, onStartChat: () -> Unit, onCreateGroup: () -> Unit) {
    val colors = BipeTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (scope == ChatScope.GRUPOS) {
            Text(stringResource(R.string.bipe_groups_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_groups_empty_body), color = colors.inkSecondary)
            // Spec chat-list-cards createGroup.
            TextButton(onClick = onCreateGroup) { Text(stringResource(R.string.bipe_groups_empty_create)) }
        } else {
            Text(stringResource(R.string.bipe_chats_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_chats_empty_body), color = colors.inkSecondary)
            TextButton(onClick = onStartChat) { Text(stringResource(R.string.bipe_action_start_chat)) }
        }
    }
}
