// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.chatlist

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
) {
    private val _hits = MutableStateFlow(emptyList<MessageHit>())
    val hits: StateFlow<List<MessageHit>> = _hits.asStateFlow()
    private val service by lazy { session.client.searchService() }
    private var handle: TaskHandle? = null
    private val results = mutableListOf<SearchServiceResult>()

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
                }
            }
        }
        runCatching { service.setQuery(pattern) }
    }

    /** The last row showed: the next page. */
    fun loadMore() {
        scope.launch { runCatching { service.paginate() } }
    }

    fun close() {
        handle?.cancel()
        handle = null
    }

    private fun hit(result: SearchServiceResult): MessageHit? {
        val message = (result as? SearchServiceResult.Message) ?: return null
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

/** One message hit: the chat, the date, the sender and the text. */
@Composable
fun MessageHitRow(hit: MessageHit, onOpen: () -> Unit) {
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
            Text(
                hit.chatName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(date, style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
        }
        Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = colors.inkSecondary, maxLines = 1)
        Text(hit.body, style = MaterialTheme.typography.bodyMedium, color = colors.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
