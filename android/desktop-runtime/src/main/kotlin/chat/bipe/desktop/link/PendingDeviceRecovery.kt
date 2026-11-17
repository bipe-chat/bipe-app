// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.account.*
import chat.bipe.desktop.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PendingRecoveryAction { SIGN_OUT, FORGET_LOCAL, FINISH_CLEANUP }
sealed interface PendingRecoveryPhase {
    data object Review : PendingRecoveryPhase
    data class Working(val action: PendingRecoveryAction) : PendingRecoveryPhase
    data class Failed(val reason: StorageCode) : PendingRecoveryPhase
    /** true is emitted only when this recovery read a durable successful-logout journal. */
    data class Done(val remoteEnded: Boolean) : PendingRecoveryPhase
    data object Closed : PendingRecoveryPhase
}

/** Takes lease ownership on entry; failed teardown retains that lease in the lifetime cleanup registry. */
fun interface PendingLogout { suspend fun logoutAndJournal(lease: AccountLease) }

class SdkPendingLogout(private val userAgent: String) : PendingLogout {
    override suspend fun logoutAndJournal(lease: AccountLease) {
        val runtime = MatrixAccountRuntime.restorePendingForLogout(lease, userAgent)
        try {
            withTimeout(30_000) { runtime.lifetime.async {
                runtime.client.logout()
                // If persistence fails after logout, keep the slot and show failure. Never infer success on retry.
                lease.beginPendingDiscard(remoteEnded = true)
            }.await() }
        } finally {
            withContext(NonCancellable) {
                if (runtime.closeAsync().await() != AccountCloseResult.Closed) throw StorageFailure(StorageCode.IO_FAILURE)
            }
        }
    }
}

/** Explicit local setup recovery, never a linked-account logout or a global device-delete operation. */
class PendingDeviceRecovery(
    val slot: AccountSlot,
    private val repository: AccountRepository,
    private val logout: PendingLogout,
    private val cleanup: suspend (AccountSlot) -> Unit = ::drainPendingOwners,
) {
    private val monitor = Any()
    private val mutablePhase = MutableStateFlow<PendingRecoveryPhase>(PendingRecoveryPhase.Review)
    val phase = mutablePhase.asStateFlow()

    /** Single flight. Re-read the record under its lock: a stale UI cannot discard a now-linked account. */
    suspend fun run(action: PendingRecoveryAction) {
        synchronized(monitor) {
            if (mutablePhase.value !is PendingRecoveryPhase.Review && mutablePhase.value !is PendingRecoveryPhase.Failed) return
            mutablePhase.value = PendingRecoveryPhase.Working(action)
        }
        try {
            cleanup(slot)
            val stored = repository.slots().firstOrNull { it.slot == slot }
                ?: throw StorageFailure(StorageCode.MISSING_STORE)
            var remoteEnded = false
            when (stored) {
                is StoredSlot.Pending -> {
                    if (action == PendingRecoveryAction.FINISH_CLEANUP) throw StorageFailure(StorageCode.LINK_INCOMPLETE)
                    val lease = repository.openPendingRecovery(slot)
                    try { lease.pendingSession() } // Guard again after acquiring its exclusive lock.
                    catch (error: Exception) { lease.close(); throw error }
                    if (action == PendingRecoveryAction.SIGN_OUT) {
                        logout.logoutAndJournal(lease) // Ownership transfers; do not close a retained native lock here.
                    } else {
                        lease.use {
                            // Separate confirmed local-forget action. It makes no server-revocation claim.
                            lease.beginPendingDiscard(remoteEnded = false)
                        }
                    }
                    cleanup(slot)
                    repository.openPendingRecovery(slot).use { remoteEnded = it.pendingDiscardStatus()
                        ?: throw StorageFailure(StorageCode.LINK_INCOMPLETE) }
                    repository.finishPendingDiscard(slot)
                }
                is StoredSlot.Discarding -> { remoteEnded = stored.remoteEnded; repository.finishPendingDiscard(slot) }
                is StoredSlot.Revoked -> repository.retryRevokedCleanup(slot)
                else -> throw StorageFailure(StorageCode.LINK_INCOMPLETE)
            }
            synchronized(monitor) { mutablePhase.value = PendingRecoveryPhase.Done(remoteEnded) }
        } catch (cancelled: CancellationException) {
            synchronized(monitor) { mutablePhase.value = PendingRecoveryPhase.Failed(StorageCode.IO_FAILURE) }
            throw cancelled
        } catch (error: Exception) {
            synchronized(monitor) { mutablePhase.value = PendingRecoveryPhase.Failed((error as? StorageFailure)?.code ?: StorageCode.IO_FAILURE) }
        }
    }

    fun close(): Boolean = synchronized(monitor) {
        if (mutablePhase.value is PendingRecoveryPhase.Working) false
        else { mutablePhase.value = PendingRecoveryPhase.Closed; true }
    }
}

private suspend fun drainPendingOwners(slot: AccountSlot) {
    for ((epoch, ownedSlot) in AccountLifetime.pendingCleanup()) if (ownedSlot == slot) {
        if (AccountLifetime.retryCleanup(epoch)?.await() != AccountCloseResult.Closed) throw StorageFailure(StorageCode.SLOT_BUSY)
    }
}
