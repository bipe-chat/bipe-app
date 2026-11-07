// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.groups

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.MediaLoader
import chat.bipe.app.common.displayName
import chat.bipe.app.timeline.ChatTimelineScreen
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ThreadListItem
import org.matrix.rustcomponents.sdk.ThreadListService
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** Spec thread-list (bipe-chat/bipe#667): the threads of a group. */
class ThreadListModel(private val session: AccountSession, private val roomId: String, private val scope: CoroutineScope) {
    data class State(val threads: List<ThreadListItem> = emptyList(), val isLoading: Boolean = true)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var service: ThreadListService? = null
    private var reachedEnd = false
    private var paging = false

    /** Action appear. */
    fun start() {
        if (service != null) return
        scope.launch {
            service = runCatching { session.client.getRoom(roomId)?.threadListService() }.getOrNull()
            loadMore()
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    /** Action loadMore. A failed page changes nothing. */
    suspend fun loadMore() {
        val service = service ?: return
        if (reachedEnd || paging) return
        paging = true
        val before = service.items().size
        val paged = runCatching { service.paginate() }.isSuccess
        paging = false
        if (!paged) return
        val items = service.items()
        reachedEnd = items.size == before
        _state.value = _state.value.copy(threads = items)
    }

    fun loadMoreLater() {
        scope.launch { loadMore() }
    }
}

/** State preview: the body of a text root. The device decrypts it. */
private fun ThreadListItem.preview(): String? =
    ((rootEvent.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)?.content?.body

/** Spec thread-list. */
@Composable
fun ThreadListScreen(session: AccountSession, media: MediaLoader, roomId: String, onBack: () -> Unit) {
    val colors = BipeTheme.colors
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) { ThreadListModel(session, roomId, scope) }
    DisposableEffect(model) {
        model.start()
        onDispose { }
    }
    var openRoot by rememberSaveable { mutableStateOf<String?>(null) }
    openRoot?.let { root ->
        BackHandler { openRoot = null }
        ChatTimelineScreen(session, media, roomId, onBack = { openRoot = null }, threadRoot = root)
        return
    }
    val state by model.state.collectAsState()
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize().testTag(BipeScreen.THREAD_LIST.id), color = colors.surfaceBase) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            GroupTopBar(stringResource(R.string.bipe_thread_list_title), onBack = onBack)
            if (state.threads.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoading) {
                        CircularProgressIndicator(color = colors.accentFill)
                    } else {
                        Text(stringResource(R.string.bipe_thread_list_empty), color = colors.inkSecondary)
                    }
                }
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.threads, key = { _, thread -> thread.rootEvent.eventId }) { index, thread ->
                    if (index == state.threads.lastIndex) LaunchedEffect(thread.rootEvent.eventId) { model.loadMoreLater() }
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { openRoot = thread.rootEvent.eventId }
                            .semantics(mergeDescendants = true) {}
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            thread.rootEvent.senderProfile.displayName(thread.rootEvent.sender),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.inkSecondary,
                        )
                        thread.preview()?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.inkPrimary)
                        }
                        Text(
                            stringResource(R.string.bipe_thread_replies, NumberFormat.getIntegerInstance().format(thread.numReplies.toLong())),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
        }
    }
}
