// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The pinned SDK replaces subscriptions; lists lease one union in each immutable account session. */
class BipeRoomSubscriptions(private val apply: suspend (List<String>) -> Unit) {
    private val mutex = Mutex()
    private val owners = mutableMapOf<String, Set<String>>()
    private var applied: Set<String>? = null
    private var closed = false

    suspend fun update(owner: String, roomIds: List<String>) = mutex.withLock {
        if (closed) return@withLock
        owners[owner] = roomIds.filter { it.isNotEmpty() }.toSet()
        reconcile()
    }

    suspend fun remove(owner: String) = mutex.withLock {
        owners.remove(owner)
        reconcile()
    }

    suspend fun close() = mutex.withLock {
        closed = true
        owners.clear()
        reconcile()
    }

    private suspend fun reconcile() {
        val target = owners.values.flatten().toSet()
        if (target == applied) return
        try {
            apply(target.sorted())
            applied = target
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not acknowledge failure; a later owner update retries even when the desired union is unchanged.
        }
    }
}
