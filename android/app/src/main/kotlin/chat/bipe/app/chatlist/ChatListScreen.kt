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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.settings.SettingsHost
import chat.bipe.phosphor.PhosphorIcon
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeApplication
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.TermsReacceptModel
import chat.bipe.app.account.TermsReacceptScreen
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.handle.HandlePickScreen
import chat.bipe.app.handle.HandlePromptCard
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.app.account.AccountSession
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
import chat.bipe.app.recovery.RecoveryAlertModel
import chat.bipe.app.recovery.RecoveryAlertScreen
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.launch

/**
 * The signed-in app: chat-list, chat-timeline on top of it when a chat is open, handle-pick from the prompt card, and
 * terms-reaccept over everything when the legal version changed.
 */
@Composable
fun HomeScreen(session: AccountSession, api: BipeApiClient, initialRoomId: String?, foregroundCount: Int, onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val undecryptable = stringResource(R.string.bipe_app_timeline_undecryptable)
    val model = remember(session) { ChatListModel(session, scope, undecryptable) }
    val media = remember(session) { MediaLoader(session.client) }
    val handle = remember(session) { HandlePickModel(context, api, session, scope) }
    val terms = remember(session) { TermsReacceptModel(api, session, scope) }
    val encryption = remember(session) { EncryptionStatusModel(session, scope, context.applicationContext) }
    val recoveryAlert = remember(session) { RecoveryAlertModel(context.applicationContext, api, session, scope) }
    val alertNotice by recoveryAlert.notice.collectAsState()
    var openRoomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var showsHandlePick by rememberSaveable { mutableStateOf(false) }
    var showsSettings by rememberSaveable { mutableStateOf(false) }
    val termsPhase by terms.phase.collectAsState()
    val app = context.applicationContext as BipeApplication
    DisposableEffect(model) {
        model.start()
        handle.load()
        onDispose { model.close() }
    }
    DisposableEffect(encryption) {
        encryption.observe()
        onDispose { encryption.close() }
    }
    // terms-reaccept, action check: after sign-in and on every return to the foreground.
    LaunchedEffect(foregroundCount) { terms.check() }
    // recovery-alert, action check: on launch and on every return to the foreground.
    LaunchedEffect(foregroundCount) { recoveryAlert.check() }
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
    // Spec notification-service, action open.
    val roomToOpen by app.roomToOpen.collectAsState()
    LaunchedEffect(roomToOpen) {
        roomToOpen?.let {
            showsSettings = false
            peopleStack = emptyList()
            showsHandlePick = false
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
        showsSettings -> SettingsHost(session, api, onClose = { showsSettings = false }, onSignedOut = onSignOut)
        showsHandlePick -> {
            BackHandler { showsHandlePick = false }
            HandlePickScreen(handle, onClose = { showsHandlePick = false })
        }
        peopleStack.isNotEmpty() -> {
            BackHandler { navigation.pop() }
            PeopleScreen(peopleStack.last(), people, navigation)
        }
        else -> GroupHost(session, api, media, roomId, foregroundCount, onOpenRoom = { openRoomId = it }) { groups ->
            if (roomId != null) {
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
                ChatListScreen(
                    model,
                    media,
                    handle,
                    onOpenHandlePick = { showsHandlePick = true },
                    onOpenChat = { openRoomId = it },
                    onOpenSettings = { showsSettings = true },
                    banner = { HistoryBanner(encryption) },
                    onCreateGroup = groups.createGroup,
                    onDeleteDm = groups.deleteDm,
                    onInvite = groups.openInvites,
                    onOpenPeople = navigation.push,
                )
            }
        }
    }
    EncryptionSheet(encryption)
}

/** Spec chat-list. */
@Composable
fun ChatListScreen(
    model: ChatListModel,
    media: MediaLoader,
    handle: HandlePickModel,
    onOpenHandlePick: () -> Unit,
    onOpenChat: (String) -> Unit,
    /** The temporary entry to settings-root until the Você tab (bipe-chat/bipe#567) is on main. */
    onOpenSettings: (() -> Unit)? = null,
    banner: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
    onInvite: () -> Unit = {},
    onOpenPeople: (PeopleRoute) -> Unit,
) {
    val colors = BipeTheme.colors
    val phase by model.phase.collectAsState()
    val allChats by model.chats.collectAsState()
    val chats = withoutClearedDms(allChats, { it.roomId }, { it.lastActivity })
    val handleState by handle.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bipe_app_chat_list_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                )
                PeopleMenu(onOpenPeople)
                CreateGroupButton(onCreateGroup, onInvite)
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painterResource(PhosphorIcon.GEAR.drawable),
                            contentDescription = stringResource(R.string.bipe_settings_section),
                            tint = colors.glyphPrimary,
                        )
                    }
                }
            }
            if (handleState.showsPrompt) HandlePromptCard(handle, open = onOpenHandlePick)
            // Spec encryption-status: the banner above the chat list.
            banner()
            when (phase) {
                ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
                ChatListModel.Phase.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bipe_app_chat_list_empty), color = colors.inkSecondary)
                }
                ChatListModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(chats, key = { it.roomId }) { chat ->
                        DmSwipeRow(chat.isDm, onDelete = { onDeleteDm(chat.roomId) }) { ChatRow(chat, media) { onOpenChat(chat.roomId) } }
                        if (chat.roomId == chats.lastOrNull()?.roomId) {
                            LaunchedEffect(chats.size) { model.loadMore() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, media: MediaLoader, onClick: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, chat.avatarUrl, media)
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
