// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.content.Context
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeHandleChange
import chat.bipe.shared.api.BipeOpenIdTokens
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Handle changes in chats (ADR 0066). The Kotlin form of BipeKit BipeHandleChanges.swift: the same
// line text, placement rule and cache, so both apps show the same lines.

/** The timeline line of a change. `name` is the person's display name in the room. `null` when the change has neither handle. */
fun BipeHandleChange.line(context: Context, name: String): String? = when {
    old != null && new != null -> context.getString(R.string.bipe_handle_changed, name, new, old)
    new != null -> context.getString(R.string.bipe_handle_set, name, new)
    old != null -> context.getString(R.string.bipe_handle_removed, name, old)
    else -> null
}

/** Where the handle-change lines go in a loaded timeline (ADR 0066). */
object BipeHandleChangePlacement {
    /**
     * The insertion index for each change that belongs in the loaded range, sorted by index then time.
     * [anchors] holds the time of each loaded row, oldest first; `null` for rows without a time.
     * - A change older than the first loaded row is outside the range (back-pagination brings it in later).
     * - A change newer than the last row shows at the end only when the timeline is live ([includesEnd]).
     * - [members]: people who are members of the room now. Others get no line.
     * - [joinedAt]: join times seen in the loaded rows. A change before the person joined gets no line.
     */
    fun placements(
        changes: List<BipeHandleChange>,
        anchors: List<Instant?>,
        members: Set<String>,
        joinedAt: Map<String, Instant>,
        includesEnd: Boolean,
    ): List<Pair<Int, BipeHandleChange>> {
        val timed = anchors.withIndex().mapNotNull { (index, at) -> at?.let { index to it } }
        val first = timed.firstOrNull() ?: return emptyList()
        val last = timed.last()
        val result = mutableListOf<Pair<Int, BipeHandleChange>>()
        for (change in changes) {
            if (change.userId !in members) continue
            if (change.at < first.second) continue
            val joined = joinedAt[change.userId]
            if (joined != null && change.at < joined) continue
            if (change.at > last.second) {
                if (includesEnd) result += (last.first + 1) to change
                continue
            }
            // Before the first row that is newer than the change.
            val index = timed.firstOrNull { it.second > change.at }?.first ?: (last.first + 1)
            result += index to change
        }
        return result.sortedWith(compareBy({ it.first }, { it.second.at }))
    }
}

/**
 * The handle change history of the people in the user's rooms, cached on the device (ADR 0066).
 * One instance per account. Fetches through `POST /v1/handle-changes` in chunks of 100, with `since`
 * set to the latest cached change of the chunk's people. A failed fetch keeps the cache.
 */
class BipeHandleChangeStore(
    private val api: BipeApiClient,
    userId: String,
    cacheDirectory: File?,
    /** The account's OpenID token for the core routes (bipe-chat/bipe#646). */
    private val openId: BipeOpenIdTokens,
) {
    private val file: File? = cacheDirectory?.let { dir ->
        val safeName = userId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        File(dir, "bipe-handle-changes-$safeName.json")
    }
    private val mutex = Mutex()
    private var changesByUser: MutableMap<String, MutableList<BipeHandleChange>> = mutableMapOf()
    /** People asked about at least once. */
    private var fetchedUsers: MutableSet<String> = mutableSetOf()
    private val inFlight = mutableSetOf<String>()
    private var loaded = false
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when new changes arrive. */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    @Serializable
    private data class Cache(
        @SerialName("changes") val changes: List<BipeHandleChange>,
        @SerialName("fetched_users") val fetchedUsers: List<String>,
    )

    /** Cached changes of these people, oldest first. */
    suspend fun changes(userIds: Set<String>): List<BipeHandleChange> = mutex.withLock {
        load()
        userIds.flatMap { changesByUser[it].orEmpty() }.sortedBy { it.at }
    }

    /** Fetches new changes for these people. Failures keep the cache; the next call tries again. */
    suspend fun refresh(userIds: Collection<String>) {
        val wanted = mutex.withLock {
            load()
            (userIds.toSet() - inFlight).also { inFlight += it }
        }
        if (wanted.isEmpty()) return
        try {
            // People without cached changes go without `since`; the others share the oldest of their latest times.
            val (known, unknown) = mutex.withLock { wanted.sorted().partition { changesByUser[it]?.isNotEmpty() == true } }
            var gotNew = false
            for (chunk in unknown.chunked(BipeHandleChange.BATCH_SIZE)) gotNew = fetch(chunk, since = null) || gotNew
            for (chunk in known.chunked(BipeHandleChange.BATCH_SIZE)) gotNew = fetch(chunk, since(chunk)) || gotNew
            save()
            if (gotNew) _changes.tryEmit(Unit)
        } finally {
            mutex.withLock { inFlight -= wanted }
        }
    }

    /** The latest cached time of each person, then the oldest of those. `null` when one of them has no cached change. */
    private suspend fun since(userIds: List<String>): Instant? = mutex.withLock {
        val latest = userIds.map { changesByUser[it]?.lastOrNull()?.at }
        if (latest.any { it == null }) null else latest.filterNotNull().minOrNull()
    }

    /** `true` when the answer had a change the cache did not have. */
    private suspend fun fetch(userIds: List<String>, since: Instant?): Boolean {
        val answer = runCatching {
            BipeApiClient.withOpenIdRetry(openId) { token -> api.handleChanges(userIds, since, token) }
        }.getOrNull() ?: return false
        return mutex.withLock {
            fetchedUsers += userIds
            var gotNew = false
            for (change in answer) {
                if (change.userId !in userIds) continue
                val list = changesByUser.getOrPut(change.userId) { mutableListOf() }
                if (change in list) continue
                list += change
                list.sortBy { it.at }
                gotNew = true
            }
            gotNew
        }
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        val cache = withContext(Dispatchers.IO) {
            runCatching { file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(Cache.serializer(), it) } }.getOrNull()
        } ?: return
        changesByUser = cache.changes.groupBy { it.userId }.mapValues { (_, list) -> list.sortedBy { it.at }.toMutableList() }.toMutableMap()
        fetchedUsers = cache.fetchedUsers.toMutableSet()
    }

    private suspend fun save() {
        val file = file ?: return
        val text = mutex.withLock {
            json.encodeToString(Cache.serializer(), Cache(changesByUser.values.flatten(), fetchedUsers.sorted()))
        }
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(file)
            }
        }
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
