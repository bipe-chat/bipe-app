// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.BipeHandleChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Duration.Companion.minutes

/**
 * Handle change history for the people in the user's rooms (ADR 0066), from `POST /v1/handle-changes`.
 * The timeline shows each change as a local line. One instance per user session; the cache lives in memory.
 *
 * The app asks on room open (the members of that room) and after each `resolve` refresh of [BipeHandleResolver].
 */
class BipeHandleChanges(
    private val apiClient: BipeApiClient,
    /** Only Matrix IDs on this server have handles. */
    private val serverName: String,
    private val scope: CoroutineScope,
    private val accessToken: suspend () -> String?,
    /** Milliseconds since the epoch. Tests can pass a fake. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val changesFlow = MutableStateFlow<Map<String, List<BipeHandleChange>>>(emptyMap())

    /** Matrix ID to its changes, oldest first. Users without changes are absent. */
    val changes: StateFlow<Map<String, List<BipeHandleChange>>> = changesFlow.asStateFlow()

    private val mutex = Mutex()

    /** Matrix ID to the time (ms) of the last request for it. */
    private val lastAsked = mutableMapOf<String, Long>()

    /** Asks for the changes of [userIds] in the background. A user asked less than [MIN_INTERVAL_MILLIS] ago is skipped. */
    fun refresh(userIds: Collection<String>) {
        val candidates = userIds.filter { it.startsWith("@") && it.endsWith(":$serverName") }.distinct()
        if (candidates.isEmpty()) return
        scope.launch { fetch(candidates) }
    }

    private suspend fun fetch(userIds: List<String>) = mutex.withLock {
        val now = clock()
        val due = userIds.filter { (lastAsked[it] ?: Long.MIN_VALUE) + MIN_INTERVAL_MILLIS <= now }
        for (chunk in due.chunked(BATCH_SIZE)) {
            // `since` is the latest change already cached. One value per request: the oldest of the chunk,
            // or none when a user has no cached change. Duplicates are dropped below.
            val cached = changesFlow.value
            val latest = chunk.map { userId -> cached[userId]?.lastOrNull()?.at }
            val since = if (latest.any { it == null }) null else latest.filterNotNull().minOrNull()
            val received = try {
                BipeApiClient.withTokenRetry(accessToken) { token -> apiClient.handleChanges(chunk, since, token) }
            } catch (error: BipeApiException) {
                Timber.w(error, "Bipe: handle changes not loaded")
                return@withLock
            }
            chunk.forEach { lastAsked[it] = now }
            if (received.isEmpty()) continue
            changesFlow.update { current ->
                val merged = current.toMutableMap()
                for ((userId, list) in received.groupBy { it.userId }) {
                    merged[userId] = (current[userId].orEmpty() + list).distinct().sortedBy { it.at }
                }
                merged
            }
        }
    }

    private companion object {
        /** The API takes at most 100 IDs per call. */
        const val BATCH_SIZE = 100

        /** The API allows 60 calls per minute per account. */
        val MIN_INTERVAL_MILLIS = 1.minutes.inWholeMilliseconds
    }
}
