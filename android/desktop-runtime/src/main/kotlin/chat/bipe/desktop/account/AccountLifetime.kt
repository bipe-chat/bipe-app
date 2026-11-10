// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.AccountLease
import chat.bipe.desktop.storage.AccountSlot
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun interface AccountResource { suspend fun close() }

sealed interface AccountCloseResult {
    data object Closed : AccountCloseResult
    data class Blocked(val reason: StorageCode) : AccountCloseResult
}

/** One account owns every task/resource. Closed epochs never publish into a new account or new attempt. */
class AccountLifetime(val lease: AccountLease) {
    val epoch: UUID = UUID.randomUUID()
    private val monitor = Any()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private sealed interface Phase {
        class Active(val resources: MutableList<AccountResource>) : Phase
        class Closing(val resources: MutableList<AccountResource>, val completion: CompletableDeferred<AccountCloseResult>) : Phase
        class Blocked(val resources: MutableList<AccountResource>, val reason: StorageCode) : Phase
        data object Closed : Phase
    }
    private var phase: Phase = Phase.Active(mutableListOf(AccountResource { lease.close() }))

    fun isCurrent(epoch: UUID = this.epoch): Boolean = synchronized(monitor) {
        epoch == this.epoch && phase is Phase.Active && job.isActive && lease.isCurrent()
    }

    fun requireCurrent() {
        if (!isCurrent()) throw StorageFailure(StorageCode.STALE_OWNER)
    }

    /** The action is a synchronous state commit, never SDK/network I/O. */
    fun publish(epoch: UUID, action: () -> Unit): Boolean = synchronized(monitor) {
        if (!isCurrent(epoch)) false else { action(); true }
    }

    fun <T> async(operation: suspend () -> T): Deferred<T> = scope.async {
        requireCurrent()
        operation()
    }

    fun launch(operation: suspend () -> Unit): Job = scope.launch {
        requireCurrent()
        operation()
    }

    suspend fun <T : AccountResource> own(resource: T): T {
        val accepted = synchronized(monitor) {
            (phase as? Phase.Active)?.takeIf { job.isActive && lease.isCurrent() }?.resources?.add(resource) == true
        }
        if (!accepted) {
            withContext(NonCancellable) { resource.close() }
            throw StorageFailure(StorageCode.STALE_OWNER)
        }
        return resource
    }

    /** Cleanup runs outside this account's cancelled job, so an SDK callback cannot wait for itself. */
    fun closeAsync(): Deferred<AccountCloseResult> {
        val closing = synchronized(monitor) {
            when (val current = phase) {
                is Phase.Closing -> return current.completion
                Phase.Closed -> return CompletableDeferred(AccountCloseResult.Closed)
                is Phase.Active -> Phase.Closing(current.resources, CompletableDeferred())
                is Phase.Blocked -> Phase.Closing(current.resources, CompletableDeferred())
            }.also { phase = it }
        }
        lease.retire()
        job.cancel()
        cleanup.launch {
            try {
                // In-flight native construction must finish/cancel before the lifetime lock can be released.
                withTimeout(10_000) { job.join() }
                while (closing.resources.isNotEmpty()) {
                    closing.resources.last().close()
                    closing.resources.removeAt(closing.resources.lastIndex)
                }
                synchronized(monitor) { phase = Phase.Closed }
                unresolved.remove(epoch, this@AccountLifetime)
                closing.completion.complete(AccountCloseResult.Closed)
            } catch (error: Exception) {
                val reason = (error as? StorageFailure)?.code ?: StorageCode.IO_FAILURE
                // Keep the remaining resources, especially the exclusive slot lock, for an explicit retry.
                synchronized(monitor) {
                    phase = Phase.Blocked(closing.resources, reason)
                    // Factory failures may never return a runtime to the UI. Keep their lock/resources reachable.
                    unresolved[epoch] = this@AccountLifetime
                }
                closing.completion.complete(AccountCloseResult.Blocked(reason))
            }
        }
        return closing.completion
    }

    companion object {
        private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val unresolved = ConcurrentHashMap<UUID, AccountLifetime>()

        /** Safe identifiers only; a settings/recovery surface can retry instead of losing a failed owner to GC. */
        fun pendingCleanup(): Map<UUID, AccountSlot> = unresolved.mapValues { it.value.lease.slot }
        fun retryCleanup(epoch: UUID): Deferred<AccountCloseResult>? = unresolved[epoch]?.closeAsync()
    }
}
