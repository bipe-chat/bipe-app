// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountTaskGroupTest {
    @Test fun aRoomCanCloseWithoutRetiringItsAccountAndOldCallbacksCannotPublish() = runBlocking {
        val root = Files.createTempDirectory("bipe-room-owner-")
        val owner = AccountLifetime(AccountRepository(root, MemoryVault(), MemoryProtection()).provision())
        try {
            val group = AccountTaskGroup.create(owner)
            val releases = AtomicInteger()
            group.own(NativeResource { releases.incrementAndGet(); Unit })
            var updates = 0
            group.publish { updates++ }
            group.close()
            group.publish { updates++ }
            group.close()
            assertEquals(1, updates)
            assertEquals(1, releases.get())
            assertTrue(owner.isCurrent())
        } finally { owner.closeAsync().await(); root.toFile().deleteRecursively() }
    }

    @Test fun nativeRoomReleaseFailureKeepsTheAccountLockUntilRetry() = runBlocking {
        val root = Files.createTempDirectory("bipe-room-retry-")
        val repository = AccountRepository(root, MemoryVault(), MemoryProtection())
        val lease = repository.provision()
        val owner = AccountLifetime(lease)
        try {
            val group = AccountTaskGroup.create(owner)
            val releases = AtomicInteger()
            group.own(NativeResource {
                if (releases.incrementAndGet() == 1) throw StorageFailure(StorageCode.IO_FAILURE)
            })
            assertTrue(owner.closeAsync().await() is AccountCloseResult.Blocked)
            expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
            assertEquals(AccountCloseResult.Closed, AccountLifetime.retryCleanup(owner.epoch)!!.await())
            repository.open(lease.slot).close()
        } finally { owner.closeAsync().await(); root.toFile().deleteRecursively() }
    }
}
