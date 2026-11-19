// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.app.common.toVectorDiff
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.MatrixAccountRuntime
import chat.bipe.search.MessageSearchQuery
import chat.bipe.shared.generated.BipeRegistries
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.matrix.rustcomponents.sdk.*
import uniffi.matrix_sdk_ui.SearchServicePaginationState

data class DesktopSearchRoom(val id: String, val name: String, val isDm: Boolean)
data class DesktopSearchHit(val room: DesktopSearchRoom, val eventId: String, val sender: String,
                            val body: String, val timestamp: Long)

class DesktopMessageSearch private constructor(private val runtime: MatrixAccountRuntime,
                                               private val owner: AccountTaskGroup, private val labels: ChatLabels) {
    private val searchLock = Mutex()
    private val recentChanges = Channel<(List<String>) -> List<String>>(Channel.UNLIMITED)
    private val mutableRecent = MutableStateFlow<List<String>>(emptyList())
    val recent = mutableRecent.asStateFlow()

    data class Page(val hits: List<DesktopSearchHit>, val hasMore: Boolean, val loading: Boolean)
    class Query internal constructor(val text: String, internal val pattern: String, internal val room: String?) {
        internal val requests = Channel<Unit>(Channel.CONFLATED)
        internal val mutable = MutableStateFlow<LoadPhase<Page>>(LoadPhase.Loading)
        val phase = mutable.asStateFlow()
        fun more() { requests.trySend(Unit) }
    }

    fun query(text: String, roomId: String?): Query? {
        val trimmed = text.trim().take(MAX_QUERY)
        return MessageSearchQuery.make(trimmed)?.let { Query(trimmed, it, roomId) }
    }

    /** The UI owns the query and cancels this when text, account or room changes. Only one SDK query can run. */
    suspend fun run(query: Query) {
        val task = owner.async {
            searchLock.withLock {
                owner.publish { query.mutable.value = LoadPhase.Loading }
                try { search(query) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { owner.publish { query.mutable.value = LoadPhase.Failed } }
            }
        }
        try { task.await() }
        finally { withContext(NonCancellable) { task.cancelAndJoin() } }
    }

    private suspend fun search(query: Query) {
        val service = runtime.client.searchService()
        try {
            val rooms = joinedRooms().filter { query.room == null || query.room == it.id }.associateBy { it.id }
            val own = runtime.client.userId()
            service.setQuery(query.pattern)
            val updates = Channel<List<SearchServiceResultsUpdate>>(Channel.UNLIMITED, onUndeliveredElement = ::dispose)
            val listener = service.subscribeToResults(object : SearchServiceResultsListener {
                override fun onUpdate(batch: List<SearchServiceResultsUpdate>) {
                    if (updates.trySend(batch).isFailure) dispose(batch)
                }
            })
            try {
                coroutineScope {
                    val snapshot = SnapshotVector<DesktopSearchHit>()
                    val visible = MutableStateFlow<List<DesktopSearchHit>>(emptyList())
                    val paging = MutableStateFlow(false)
                    val filled = AtomicInteger()
                    fun publish() {
                        val pages = service.paginationState()
                        val hasMore = (pages as? SearchServicePaginationState.Idle)?.endReached != true
                        owner.publish { query.mutable.value = LoadPhase.Ready(Page(visible.value, hasMore, paging.value)) }
                    }
                    launch {
                        for (batch in updates) {
                            try {
                                batch.forEach { update ->
                                    snapshot.apply(update.toVectorDiff().snapshot { toHit(it, own, rooms) })
                                }
                            } finally { dispose(batch) }
                            visible.value = snapshot.visible().distinctBy { it.room.id to it.eventId }
                            if (query.room != null && visible.value.size < 20 && filled.get() < 5) query.more()
                            publish()
                        }
                    }
                    for (ignored in query.requests) {
                        if ((service.paginationState() as? SearchServicePaginationState.Idle)?.endReached != false) continue
                        paging.value = true
                        filled.incrementAndGet()
                        publish()
                        try { service.paginate() } finally { paging.value = false }
                        publish()
                        if (query.room != null && visible.value.size < 20 && filled.get() < 5) query.more()
                    }
                }
            } finally {
                try { listener.cancel() } finally { listener.destroy(); updates.cancel() }
            }
        } finally { service.destroy() }
    }

    suspend fun joinedRooms(): List<DesktopSearchRoom> {
        val task = owner.async {
            val hidden = listOf(BipeRegistries.DRAFTS_ACCOUNT_DATA_TYPE, BipeRegistries.WORK_NOTES_ACCOUNT_DATA_TYPE)
                .mapNotNull { type ->
                    runtime.client.accountData(type)?.let { json ->
                        val data = Json.parseToJsonElement(json) as? JsonObject
                        (data?.get("room_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    }
                }.toSet()
            val rooms = runtime.client.rooms()
            try {
                rooms.mapNotNull { room ->
                    if (room.id() in hidden) return@mapNotNull null
                    val info = try { room.roomInfo() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@mapNotNull null }
                    try {
                        if (info.membership != Membership.JOINED || info.isSpace || (info.isDirect && info.activeMembersCount < 2uL))
                            return@mapNotNull null
                        DesktopSearchRoom(info.id, info.displayName?.takeIf { it.isNotBlank() } ?: labels.noName, info.isDm)
                    } finally { info.destroy() }
                }
            } finally { rooms.forEach { it.destroy() } }
        }
        try { return task.await() } finally { task.cancel() }
    }

    fun remember(query: String) {
        val value = query.trim().take(MAX_QUERY)
        if (value.isNotEmpty()) record { (listOf(value) + it.filterNot { prior -> prior == value }).take(RECENT_LIMIT) }
    }

    fun clearRecent() = record { emptyList() }

    private fun record(change: (List<String>) -> List<String>) {
        owner.publish { recentChanges.trySend(change) }
    }

    private suspend fun persistRecentChanges() {
        for (change in recentChanges) {
            try {
                val values = change(mutableRecent.value)
                runtime.lifetime.lease.writeRecentSearches(JsonArray(values.map { JsonPrimitive(it) }).toString())
                owner.publish { mutableRecent.value = values }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The protected store retains the last committed list. */ }
        }
    }

    private fun toHit(value: SearchServiceResult, own: String, rooms: Map<String, DesktopSearchRoom>): DesktopSearchHit? {
        val event = value as? SearchServiceResult.Message ?: return null
        val room = rooms[event.roomId] ?: return null
        val result = event.result
        val content = (result.content as? TimelineItemContent.MsgLike)?.content ?: return null
        val message = (content.kind as? MsgLikeKind.Message)?.content ?: return null
        val sender = if (result.sender == own) labels.you
            else (result.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() } ?: result.sender
        return DesktopSearchHit(room, result.eventId, sender,
            (MessageMedia.of(message.msgType)?.caption ?: message.body).lineSequence().joinToString(" "), result.timestamp.toLong())
    }

    companion object {
        const val MAX_QUERY = 256
        private const val RECENT_LIMIT = 10

        suspend fun open(runtime: MatrixAccountRuntime, labels: ChatLabels): DesktopMessageSearch {
            val owner = AccountTaskGroup.create(runtime.lifetime)
            val search = DesktopMessageSearch(runtime, owner, labels)
            owner.own(AccountResource { search.recentChanges.cancel() })
            owner.async {
                val saved = runCatching {
                    val value = runtime.lifetime.lease.readRecentSearches() ?: return@runCatching emptyList()
                    Json.parseToJsonElement(value).jsonArray.mapNotNull {
                        (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content?.take(MAX_QUERY)
                    }.distinct().take(RECENT_LIMIT)
                }.getOrDefault(emptyList())
                owner.publish { search.mutableRecent.value = saved }
            }.await()
            owner.launch { search.persistRecentChanges() }
            return search
        }

        private fun dispose(batch: List<SearchServiceResultsUpdate>) {
            batch.forEach { it.toVectorDiff().forEachValue { result -> result.destroy() } }
        }
    }
}
