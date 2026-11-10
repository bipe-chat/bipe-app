// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A room/list can end before its account. Its native resources still precede the client in account teardown. */
class AccountTaskGroup private constructor(private val owner: AccountLifetime) : AccountResource {
    private val job = owner.childJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val monitor = Any()
    private val closing = Mutex()
    private enum class Phase { ACTIVE, CLOSING, BLOCKED, CLOSED }
    private var phase = Phase.ACTIVE
    private val resources = mutableListOf<AccountResource>()

    fun requireCurrent() {
        owner.requireCurrent()
        synchronized(monitor) {
            if (phase != Phase.ACTIVE || !job.isActive) throw StorageFailure(StorageCode.STALE_OWNER)
        }
    }

    fun publish(action: () -> Unit) = owner.publish(owner.epoch) {
        synchronized(monitor) { if (phase == Phase.ACTIVE && job.isActive) action() }
    }

    fun <T> async(operation: suspend () -> T) = scope.async { requireCurrent(); operation() }
    fun launch(operation: suspend () -> Unit): Job = scope.launch { requireCurrent(); operation() }

    suspend fun <T : AccountResource> own(resource: T): T {
        val accepted = synchronized(monitor) {
            if (phase == Phase.ACTIVE && job.isActive) { resources += resource; true } else false
        }
        if (!accepted) {
            withContext(NonCancellable) { resource.close() }
            throw StorageFailure(StorageCode.STALE_OWNER)
        }
        return resource
    }

    /** Call from outside this group's own tasks, which are joined before any native resource is released. */
    override suspend fun close() = withContext(NonCancellable) { closing.withLock {
        if (synchronized(monitor) { phase == Phase.CLOSED }) return@withLock
        synchronized(monitor) { phase = Phase.CLOSING }
        job.cancel()
        try {
            withTimeout(10_000) { job.join() }
            while (resources.isNotEmpty()) {
                resources.last().close()
                resources.removeAt(resources.lastIndex)
            }
            synchronized(monitor) { phase = Phase.CLOSED }
        } catch (error: Exception) {
            synchronized(monitor) { phase = Phase.BLOCKED }
            throw error
        }
    } }

    companion object {
        suspend fun create(owner: AccountLifetime) = owner.own(AccountTaskGroup(owner))
    }
}

/** Explicit idempotent release for a native SDK object/callback handle. */
class NativeResource(private val release: suspend () -> Unit) : AccountResource {
    private enum class Phase { OPEN, CLOSED }
    private var phase = Phase.OPEN
    override suspend fun close() {
        if (phase == Phase.CLOSED) return
        release()
        phase = Phase.CLOSED
    }
}
