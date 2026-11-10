// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.AccountRepository
import chat.bipe.desktop.storage.MemoryProtection
import chat.bipe.desktop.storage.MemoryVault
import chat.bipe.desktop.storage.StorageCode
import chat.bipe.desktop.storage.StorageFailure
import chat.bipe.desktop.storage.expect
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AccountLifetimeTest {
    private val root = Files.createTempDirectory("bipe-owner-test-")
    private val repository = AccountRepository(root, MemoryVault(), MemoryProtection())
    private val lease = repository.provision()
    private val owner = AccountLifetime(lease)

    @After fun cleanup() = runBlocking {
        owner.closeAsync().await()
        lease.close()
        root.toFile().deleteRecursively()
        Unit
    }

    @Test fun closesInReverseOrderBeforeReleasingTheSlotLock() = runBlocking {
        val order = mutableListOf<Int>()
        owner.own(AccountResource { order += 1 })
        owner.own(AccountResource {
            expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
            order += 2
        })
        assertEquals(AccountCloseResult.Closed, owner.closeAsync().await())
        assertEquals(listOf(2, 1), order)
        repository.open(lease.slot).close()
    }

    @Test fun failedCleanupKeepsTheSlotLockAndCanBeRetried() = runBlocking {
        val attempts = AtomicInteger()
        owner.own(AccountResource {
            if (attempts.incrementAndGet() == 1) throw StorageFailure(StorageCode.IO_FAILURE)
        })
        assertTrue(owner.closeAsync().await() is AccountCloseResult.Blocked)
        assertFalse(owner.isCurrent())
        assertEquals(lease.slot, AccountLifetime.pendingCleanup()[owner.epoch])
        expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
        assertEquals(AccountCloseResult.Closed, AccountLifetime.retryCleanup(owner.epoch)!!.await())
        assertFalse(owner.epoch in AccountLifetime.pendingCleanup())
        assertEquals(2, attempts.get())
        repository.open(lease.slot).close()
    }

    @Test fun retiredEpochCannotPublishIntoTheNextOwner() = runBlocking {
        var published = 0
        assertFalse(owner.publish(UUID.randomUUID()) { published++ })
        assertTrue(owner.publish(owner.epoch) { published++ })
        owner.closeAsync().await()
        val next = AccountLifetime(repository.open(lease.slot))
        try {
            assertFalse(owner.publish(owner.epoch) { published++ })
            assertFalse(next.publish(owner.epoch) { published++ })
            assertTrue(next.publish(next.epoch) { published++ })
            assertEquals(2, published)
        } finally { next.closeAsync().await() }
    }

    @Test fun closeWaitsForCancelledWorkAndDisposesItsLateResource() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val lateCloses = AtomicInteger()
        val lateRejected = CompletableDeferred<Boolean>()
        owner.launch {
            try { started.complete(Unit); awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    finish.await()
                    try {
                        owner.own(AccountResource { lateCloses.incrementAndGet(); Unit })
                        lateRejected.complete(false)
                    } catch (failure: StorageFailure) {
                        lateRejected.complete(failure.code == StorageCode.STALE_OWNER)
                    }
                }
            }
        }
        started.await()
        val closing = owner.closeAsync()
        assertFalse(owner.isCurrent())
        expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
        finish.complete(Unit)
        assertTrue(lateRejected.await())
        assertEquals(AccountCloseResult.Closed, closing.await())
        assertEquals(1, lateCloses.get())
    }
}
