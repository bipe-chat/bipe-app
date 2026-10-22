// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeApiException
import chat.bipe.shared.api.handle
import chat.bipe.shared.api.resolveHandles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Maps Matrix IDs to handles and back, through the Bipe API. One instance per user session.
 *
 * Reads are synchronous: [handle] returns `null` at first, the resolver batches the lookup through
 * `POST /v1/handles/resolve`, and [handles] emits when the handle arrives. UI collects [handles] to recompose.
 */
class BipeHandleResolver(
    private val apiClient: BipeApiClient,
    /** Only Matrix IDs on this server have handles. */
    val serverName: String,
    private val scope: CoroutineScope,
    /** Read before every request, because Matrix access tokens are short-lived. */
    private val accessToken: suspend () -> String?,
    /** Milliseconds since the epoch. Tests can pass a fake. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val handle: String?, val expiryMillis: Long)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** Matrix ID to known handle (without "@"). Emits on every cache change. */
    val handles: StateFlow<Map<String, String?>> get() = handlesFlow.asStateFlow()
    private val handlesFlow = MutableStateFlow<Map<String, String?>>(emptyMap())

    private val lock = Any()
    private val queued = linkedSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val waiters = mutableMapOf<String, MutableList<CompletableDeferred<String?>>>()
    private var flushJob: Job? = null

    // Matrix ID to handle

    /** `true` for a user ID on the Bipe server, e.g. `@k7q2m9x4tp:bipe.net.br`. */
    fun isBipeUserId(userId: String): Boolean = userId.startsWith("@") && userId.endsWith(":$serverName")

    /** The cached handle (without "@"). Queues a lookup when the cache has no fresh value. */
    fun handle(userId: String): String? {
        if (!isBipeUserId(userId)) return null
        val entry = entries.value[userId]
        val expiryMillis = entry?.expiryMillis ?: Long.MIN_VALUE
        if (expiryMillis < clock()) enqueue(userId)
        return entry?.handle
    }

    /**
     * "@handle" for a Bipe user ID with a known handle, otherwise [text] unchanged.
     * Safe to call with any text, e.g. a title that falls back to the Matrix ID.
     */
    fun displayText(text: String): String = handle(text)?.let { "@$it" } ?: text

    /** Waits for the handle. `null` when the user has none or the lookup failed. */
    suspend fun resolve(userId: String): String? {
        if (!isBipeUserId(userId)) return null
        entries.value[userId]?.takeIf { it.expiryMillis >= clock() }?.let { return it.handle }
        val waiter = CompletableDeferred<String?>()
        synchronized(lock) { waiters.getOrPut(userId) { mutableListOf() }.add(waiter) }
        enqueue(userId)
        return waiter.await()
    }

    /** Stores a handle the app already knows, e.g. after the user changes their own. */
    fun store(handle: String?, userId: String) {
        val ttl = if (handle == null) MISSING_TTL_MILLIS else FOUND_TTL_MILLIS
        setEntry(userId, Entry(handle, clock() + ttl))
    }

    /** The profile link for a user with a known handle. */
    fun profileLink(userId: String): String? = handle(userId)?.let(BipeProfileLink::url)

    /** Swaps a matrix.to user permalink for the Bipe profile link when the handle is known. */
    fun shareUrl(permalink: String): String =
        BipeProfileLink.matrixUserId(permalink)?.let(::profileLink) ?: permalink

    // Handle to Matrix ID

    /** Exact lookup through `GET /v1/handles/{handle}`. Returns `null` when nobody holds the handle. */
    suspend fun userId(forHandle: String): String? {
        val handle = BipeHandle.sanitize(forHandle).lowercase(Locale.ROOT)
        if (BipeHandle.localProblem(handle) != null) return null

        return try {
            val record = BipeApiClient.withTokenRetry(accessToken) { token -> apiClient.handle(handle, token) }
            store(record.handle, record.userId)
            record.userId
        } catch (error: BipeApiException.Server) {
            if (error.statusCode == 404) null else throw error
        }
    }

    // Private

    private fun setEntry(userId: String, entry: Entry) {
        entries.update { it + (userId to entry) }
        handlesFlow.update { it + (userId to entry.handle) }
    }

    private fun enqueue(userId: String) {
        synchronized(lock) {
            if (userId in inFlight) return
            queued.add(userId)
            scheduleFlush()
        }
    }

    /** Call with [lock] held. */
    private fun scheduleFlush() {
        if (flushJob != null) return
        flushJob = scope.launch {
            delay(COALESCE_DELAY)
            flush()
        }
    }

    private suspend fun flush() {
        val batch = synchronized(lock) {
            flushJob = null
            val batch = queued.take(BATCH_SIZE)
            queued.removeAll(batch.toSet())
            inFlight.addAll(batch)
            if (queued.isNotEmpty()) scheduleFlush()
            batch
        }
        if (batch.isEmpty()) return

        try {
            val handles = BipeApiClient.withTokenRetry(accessToken) { token -> apiClient.resolveHandles(batch, token) }
            for (userId in batch) store(handles[userId], userId)
        } catch (_: BipeApiException) {
            // Keep the old values, with a short expiry so views do not retry on every render while offline.
            val expiry = clock() + MISSING_TTL_MILLIS
            for (userId in batch) setEntry(userId, Entry(entries.value[userId]?.handle, expiry))
        } finally {
            val resumed = synchronized(lock) {
                inFlight.removeAll(batch.toSet())
                batch.mapNotNull { userId -> waiters.remove(userId)?.let { userId to it } }
            }
            for ((userId, userWaiters) in resumed) {
                val handle = entries.value[userId]?.handle
                userWaiters.forEach { it.complete(handle) }
            }
        }
    }

    private companion object {
        val FOUND_TTL_MILLIS = 10.minutes.inWholeMilliseconds
        val MISSING_TTL_MILLIS = 2.minutes.inWholeMilliseconds
        const val BATCH_SIZE = 100
        val COALESCE_DELAY = 80.milliseconds
    }
}
