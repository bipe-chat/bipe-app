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
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.account.TermsReacceptModel
import chat.bipe.app.account.TermsReacceptScreen
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.encryption.EncryptionSheet
import chat.bipe.app.encryption.EncryptionStatusModel
import chat.bipe.app.encryption.HistoryBanner
import chat.bipe.app.handle.HandlePickModel
import chat.bipe.app.handle.HandlePickScreen
import chat.bipe.app.handle.HandlePromptCard
import chat.bipe.app.signup.SignupFlowModel
import chat.bipe.app.signup.SignupFlowScreen
import chat.bipe.app.tabs.AccountSummary
import chat.bipe.app.tabs.AppTabsModel
import chat.bipe.app.tabs.AppTabsScreen
import chat.bipe.app.groups.CreateGroupButton
import chat.bipe.app.groups.DmSwipeRow
import chat.bipe.app.groups.GroupHost
import chat.bipe.app.groups.withoutClearedDms
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.generated.BipeScreen
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
    val terms = remember(session) { TermsReacceptModel(api, session, scope) }
    val encryption = remember(session) { EncryptionStatusModel(session, scope, context.applicationContext) }
    var openRoomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var showsHandlePick by rememberSaveable { mutableStateOf(false) }
    var showsNewAccount by rememberSaveable { mutableStateOf(false) }
    val termsPhase by terms.phase.collectAsState()
    LaunchedEffect(handle) { handle.load() }
    DisposableEffect(encryption) {
        encryption.observe()
        onDispose { encryption.close() }
    }
    // terms-reaccept, action check: after sign-in and on every return to the foreground.
    LaunchedEffect(foregroundCount) { terms.check(); tabs.refreshProfiles() }
    val roomId = openRoomId
    when {
        termsPhase != TermsReacceptModel.Phase.HIDDEN -> TermsReacceptScreen(terms, onSignOut)
        showsNewAccount -> {
            BackHandler { showsNewAccount = false }
            val signup = remember(session) {
                SignupFlowModel(context, api, tabs.start.vault, tabs.start.sessionStore, scope) { created, outcome ->
                    showsNewAccount = false
                    tabs.start.didSignUp(created, outcome)
                }
            }
            SignupFlowScreen(signup, onCancel = { showsNewAccount = false })
        }
        showsHandlePick -> {
            BackHandler { showsHandlePick = false }
            HandlePickScreen(handle, onClose = { showsHandlePick = false })
        }
        else -> GroupHost(session, media, roomId, foregroundCount, onOpenRoom = { openRoomId = it }) { groups ->
            if (roomId != null) {
                BackHandler { openRoomId = null }
                ChatTimelineScreen(
                    session,
                    media,
                    roomId,
                    onBack = { openRoomId = null },
                    onHistoryHelp = encryption::openSheet,
                    onOpenGroupDetails = groups.openGroupDetails,
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
    account: AccountSummary? = null,
    onOpenVoce: () -> Unit = {},
    /** Spec encryption-status: the banner above the chat list. */
    banner: @Composable () -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onDeleteDm: (String) -> Unit = {},
) {
    val colors = BipeTheme.colors
    val allPhase by model.phase.collectAsState()
    val allChats by model.chats.collectAsState()
    val visibleChats = withoutClearedDms(allChats, { it.roomId }, { it.lastActivity })
    val chats = visibleChats.filter { it.scope == scope }
    val phase = model.phase(scope, allPhase, visibleChats)
    val handleState by handle.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (scope == ChatScope.CONVERSAS) R.string.bipe_app_chat_list_title else R.string.bipe_tab_groups),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                CreateGroupButton(onCreateGroup)
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
            if (scope == ChatScope.CONVERSAS && handleState.showsPrompt) HandlePromptCard(handle, open = onOpenHandlePick)
            // Spec encryption-status: the banner above the chat list.
            if (scope == ChatScope.CONVERSAS) banner()
            when (phase) {
                ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
                ChatListModel.Phase.EMPTY -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (scope == ChatScope.CONVERSAS) {
                        Text(stringResource(R.string.bipe_app_chat_list_empty), color = colors.inkSecondary)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.bipe_groups_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                            Text(
                                stringResource(R.string.bipe_groups_empty_body),
                                color = colors.inkSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
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
