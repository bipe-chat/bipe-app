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
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.Avatar
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat

/** The signed-in app: chat-list, and chat-timeline on top of it when a chat is open. */
@Composable
fun HomeScreen(session: AccountSession) {
    val scope = rememberCoroutineScope()
    val undecryptable = stringResource(R.string.bipe_app_timeline_undecryptable)
    val resources = LocalContext.current.resources
    val model = remember(session) { ChatListModel(session, scope, undecryptable, resources::getString) }
    val media = remember(session) { MediaLoader(session.client) }
    var openRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(model) {
        model.start()
        onDispose { model.close() }
    }
    val roomId = openRoomId
    if (roomId != null) {
        BackHandler { openRoomId = null }
        ChatTimelineScreen(session, media, roomId, onBack = { openRoomId = null })
    } else {
        ChatListScreen(model, media, onOpenChat = { openRoomId = it })
    }
}

/** Spec chat-list. */
@Composable
fun ChatListScreen(model: ChatListModel, media: MediaLoader, onOpenChat: (String) -> Unit) {
    val colors = BipeTheme.colors
    val phase by model.phase.collectAsState()
    val chats by model.chats.collectAsState()
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.CHAT_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                stringResource(R.string.bipe_app_chat_list_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.inkPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            when (phase) {
                ChatListModel.Phase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accentFill)
                }
                ChatListModel.Phase.EMPTY -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.bipe_app_chat_list_empty), color = colors.inkSecondary)
                }
                ChatListModel.Phase.LOADED -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
