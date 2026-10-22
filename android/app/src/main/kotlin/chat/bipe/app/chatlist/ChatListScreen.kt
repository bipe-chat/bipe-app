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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import chat.bipe.app.settings.SettingsHost
import chat.bipe.phosphor.PhosphorIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import chat.bipe.app.encryption.EncryptionSheet
import chat.bipe.app.encryption.EncryptionStatusModel
import chat.bipe.app.encryption.HistoryBanner
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeHaptic
import chat.bipe.shared.bipeHaptic
import androidx.compose.ui.platform.LocalView
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat

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
    val requests = remember(session) { ChatRequestsModel(context.applicationContext, session, scope) }
    val search = remember(session) { ChatSearchModel(session, model, scope) }
    val remoteBanner = remember(session) { RemoteBannerModel(context.applicationContext, api, scope) }
    var showsRequests by rememberSaveable { mutableStateOf(false) }
    val encryption = remember(session) { EncryptionStatusModel(session, scope, context.applicationContext) }
    var openRoomId by rememberSaveable { mutableStateOf(initialRoomId) }
    var showsHandlePick by rememberSaveable { mutableStateOf(false) }
    var showsSettings by rememberSaveable { mutableStateOf(false) }
    val termsPhase by terms.phase.collectAsState()
    DisposableEffect(model) {
        model.start()
        handle.load()
        requests.start()
        onDispose {
            model.close()
            requests.close()
            search.dispose()
        }
    }
    DisposableEffect(encryption) {
        encryption.observe()
        onDispose { encryption.close() }
    }
    // terms-reaccept, action check: after sign-in and on every return to the foreground.
    LaunchedEffect(foregroundCount) { terms.check() }
    // remote-banner, action refresh: when the list appears and on every return to the foreground.
    LaunchedEffect(foregroundCount) { remoteBanner.refresh() }
    val roomId = openRoomId
    when {
        termsPhase != TermsReacceptModel.Phase.HIDDEN -> TermsReacceptScreen(terms, onSignOut)
        showsSettings -> SettingsHost(session, api, onClose = { showsSettings = false }, onSignedOut = onSignOut)
        showsHandlePick -> {
            BackHandler { showsHandlePick = false }
            HandlePickScreen(handle, onClose = { showsHandlePick = false })
        }
        roomId != null -> {
            BackHandler { openRoomId = null }
            ChatTimelineScreen(session, media, roomId, onBack = { openRoomId = null }, onHistoryHelp = encryption::openSheet)
        }
        showsRequests -> ChatRequestsScreen(
            requests,
            media,
            onBack = { showsRequests = false },
            onOpenChat = {
                showsRequests = false
                openRoomId = it
            },
            onOpenWhoCanMessage = {
                showsRequests = false
                showsSettings = true
            },
        )
        else -> ChatListScreen(
            model,
            media,
            handle,
            onOpenHandlePick = { showsHandlePick = true },
            onOpenChat = { openRoomId = it },
            onOpenSettings = { showsSettings = true },
            requests = requests,
            search = search,
            remoteBanner = remoteBanner,
            onOpenRequests = { showsRequests = true },
            banner = { HistoryBanner(encryption) },
        )
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
    requests: ChatRequestsModel? = null,
    search: ChatSearchModel? = null,
    remoteBanner: RemoteBannerModel? = null,
    onOpenRequests: () -> Unit = {},
    banner: @Composable () -> Unit = {},
) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val phase by model.phase.collectAsState()
    val chats by model.chats.collectAsState()
    val unreadOnly by model.unreadOnly.collectAsState()
    val handleState by handle.state.collectAsState()
    val requestList = requests?.requests?.collectAsState()?.value.orEmpty()
    val groupInvites = requests?.groupInvites?.collectAsState()?.value.orEmpty()
    val pending = requests?.pending?.collectAsState()?.value.orEmpty()
    val requestsError = requests?.error?.collectAsState()?.value == true
    val query = search?.state?.collectAsState()?.value?.query.orEmpty()
    val rowCount = requestList.count { it.roomId !in pending }
    val invites = groupInvites.filter { it.roomId !in pending }
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.bipe_app_chat_list_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
                )
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
            remoteBanner?.let { RemoteBannerCard(it, context) }
            // Spec encryption-status: the banner above the chat list.
            banner()
            search?.let { ChatSearchField(it) }
            if (search != null && query.isNotEmpty()) {
                ChatSearchResults(search, media) { roomId ->
                    search.close()
                    onOpenChat(roomId)
                }
            } else {
                if (handleState.showsPrompt) HandlePromptCard(handle, open = onOpenHandlePick)
                UnreadChip(unreadOnly, model::toggleUnread)
                when (phase) {
                    ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.accentFill)
                    }
                    ChatListModel.Phase.EMPTY, ChatListModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // The requests row and the group invites (specs chat-requests and chat-list-filters).
                        if (rowCount > 0) item(key = "requests") { ChatRequestsRow(rowCount, onOpenRequests) }
                        if (requests != null) {
                            items(invites, key = { "invite-${it.roomId}" }) { invite ->
                                GroupInviteRow(requests, invite, media, onOpenChat)
                            }
                        }
                        if (requestsError) item(key = "requests-error") {
                            Text(stringResource(R.string.bipe_error_generic), color = colors.trustDangerInk, modifier = Modifier.padding(16.dp))
                        }
                        if (phase == ChatListModel.Phase.EMPTY) item(key = "empty") { EmptyState(unreadOnly, model::toggleUnread) }
                        items(chats, key = { it.roomId }) { chat ->
                            ChatRow(chat, media) { onOpenChat(chat.roomId) }
                            if (chat.roomId == chats.lastOrNull()?.roomId) {
                                LaunchedEffect(chats.size) { model.loadMore() }
                            }
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
private fun EmptyState(unreadOnly: Boolean, onTurnOffUnread: () -> Unit) {
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
        } else {
            Text(stringResource(R.string.bipe_chats_empty_title), style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
            Text(stringResource(R.string.bipe_chats_empty_body), color = colors.inkSecondary)
            // Action startChat: the start-chat screen comes with people and profiles (bipe-chat/bipe#568).
            Text(stringResource(R.string.bipe_action_start_chat), color = colors.inkDisabled)
        }
    }
}
