// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

import chat.bipe.app.timeline.AssistantDraftsStore
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.bipe.app.BipeTheme
import chat.bipe.app.account.AccountSession
import chat.bipe.app.common.applyDiff
import chat.bipe.app.common.displayName
import chat.bipe.app.common.toVectorDiff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import chat.bipe.phosphor.PhosphorIcon
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeScreen
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.SearchServiceResult
import org.matrix.rustcomponents.sdk.SearchServiceResultsListener
import org.matrix.rustcomponents.sdk.SearchServiceResultsUpdate
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** Type MessageHit of spec chat-search. */
data class MessageHit(
    val roomId: String,
    val eventId: String,
    val chatName: String,
    val senderName: String,
    val body: String,
    val sentAt: Long,
)

/** State messages of spec chat-search (bipe-chat/bipe#671): the SDK's local search index. */
class MessageSearchModel(
    private val session: AccountSession,
    private val scope: CoroutineScope,
    private val you: String,
    private val noName: String,
    /** Spec chat-search roomId (bipe-chat/bipe#861): only the messages of this room. `null`: every chat. */
    private val roomId: String? = null,
) {
    private val _hits = MutableStateFlow(emptyList<MessageHit>())
    val hits: StateFlow<List<MessageHit>> = _hits.asStateFlow()
    private val service by lazy { session.client.searchService() }
    private var handle: TaskHandle? = null
    private val results = mutableListOf<SearchServiceResult>()
    /** The pages loaded for [ROOM_FILL] since the last setQuery. */
    private var fillPages = 0

    /** Runs with editQuery, after its 150 ms limit. A blank query clears the hits. */
    suspend fun search(query: String) {
        val pattern = query.trim()
        if (pattern.isEmpty()) {
            _hits.value = emptyList()
            return
        }
        if (handle == null) {
            val channel = Channel<List<SearchServiceResultsUpdate>>(Channel.UNLIMITED)
            handle = service.subscribeToResults(object : SearchServiceResultsListener {
                override fun onUpdate(updates: List<SearchServiceResultsUpdate>) {
                    channel.trySend(updates)
                }
            })
            scope.launch {
                for (batch in channel) {
                    batch.forEach { results.applyDiff(it.toVectorDiff()) }
                    _hits.value = results.mapNotNull(::hit)
                    fillRoom()
                }
            }
        }
        fillPages = 0
        runCatching { service.setQuery(pattern) }
    }

    /** SearchService has no room filter: in one chat, the next page loads while too few messages match. */
    private fun fillRoom() {
        if (roomId == null || results.isEmpty() || _hits.value.size >= ROOM_FILL || fillPages >= ROOM_FILL_PAGES) return
        fillPages++
        loadMore()
    }

    /** The last row showed: the next page. */
    fun loadMore() {
        scope.launch { runCatching { service.paginate() } }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    private companion object {
        /** Spec chat-search notes (bipe-chat/bipe#861): in one chat, pages load until this many messages match. */
        const val ROOM_FILL = 20
        /** The most pages that one setQuery loads for [ROOM_FILL]. */
        const val ROOM_FILL_PAGES = 5
    }

    private fun hit(result: SearchServiceResult): MessageHit? {
        val message = (result as? SearchServiceResult.Message) ?: return null
        if (roomId != null && message.roomId != roomId) return null
        // Spec drafts notes: the drafts room never shows in search.
        if (AssistantDraftsStore.isDraftsRoom(message.roomId, session.userId)) return null
        val content = message.result.content as? TimelineItemContent.MsgLike ?: return null
        val text = (content.content.kind as? MsgLikeKind.Message)?.content ?: return null
        val room = runCatching { session.client.getRoom(message.roomId) }.getOrNull()
        val sender = message.result.sender
        val senderName = if (sender == session.userId) you else message.result.senderProfile.displayName(sender)
        return MessageHit(
            roomId = message.roomId,
            eventId = message.result.eventId,
            chatName = room?.displayName()?.takeIf { it.isNotBlank() } ?: noName,
            senderName = senderName,
            body = text.body.replace('\n', ' '),
            sentAt = message.result.timestamp.toLong(),
        )
    }
}

/** State recent of spec chat-search: the last 10 queries that opened a result, on this phone, per account. */
class RecentSearches(context: Context, userId: String) {
    private val prefs = context.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)
    private val key = userId
    private val _queries = MutableStateFlow(load())
    val queries: StateFlow<List<String>> = _queries.asStateFlow()

    fun add(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        save((listOf(trimmed) + _queries.value.filter { it != trimmed }).take(LIMIT))
    }

    /** Action clearRecent. */
    fun clear() = save(emptyList())

    private fun load(): List<String> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(queries: List<String>) {
        _queries.value = queries
        prefs.edit().putString(key, JSONArray(queries).toString()).apply()
    }

    private companion object {
        const val LIMIT = 10
    }
}

/** One message hit: the chat, the date, the sender and the text. In one chat ([showsChat] false) the row leaves out the chat name. */
@Composable
fun MessageHitRow(hit: MessageHit, showsChat: Boolean = true, onOpen: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val date = DateUtils.formatDateTime(
        context,
        hit.sentAt,
        if (DateUtils.isToday(hit.sentAt)) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row {
            if (showsChat) {
                Text(
                    hit.chatName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(date, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        }
        if (showsChat) Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary, maxLines = 1)
        Text(hit.body, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Spec chat-search with roomId (bipe-chat/bipe#861): the messages of one chat, from the chat menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatMessageSearchSheet(session: AccountSession, roomId: String, onOpenMessage: (String) -> Unit, onClose: () -> Unit) {
    val colors = BipeTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember(session, roomId) {
        MessageSearchModel(session, scope, context.getString(R.string.bipe_app_you), context.getString(R.string.bipe_dm_no_name), roomId)
    }
    DisposableEffect(model) { onDispose { model.close() } }
    var query by rememberSaveable { mutableStateOf("") }
    // The query of the last search, for the no-results line.
    var searched by remember { mutableStateOf("") }
    val hits by model.hits.collectAsState()
    // Action editQuery: at most every 150 ms.
    LaunchedEffect(model, query) {
        delay(150)
        model.search(query)
        searched = query.trim()
    }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceRaised,
    ) {
        Column(Modifier.fillMaxSize().testTag(BipeScreen.CHAT_SEARCH.id)) {
            Text(
                stringResource(R.string.bipe_search_in_chat),
                style = MaterialTheme.typography.titleMedium,
                color = colors.inkPrimary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.bipe_search_in_chat)) },
                singleLine = true,
                trailingIcon = if (query.isEmpty()) null else ({
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(PhosphorIcon.X.drawable), contentDescription = stringResource(R.string.bipe_a11y_clear_search), tint = colors.glyphSecondary)
                    }
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (searched.isNotEmpty() && hits.isEmpty()) {
                    item(key = "none") {
                        Text(stringResource(R.string.bipe_app_search_no_results, searched), color = colors.inkSecondary, modifier = Modifier.padding(16.dp))
                    }
                }
                itemsIndexed(hits, key = { _, hit -> hit.eventId }) { index, hit ->
                    if (index == hits.lastIndex) LaunchedEffect(hit.eventId) { model.loadMore() }
                    MessageHitRow(hit, showsChat = false) {
                        onClose()
                        onOpenMessage(hit.eventId)
                    }
                }
            }
        }
    }
}
