// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.account

import chat.bipe.desktop.storage.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeClientReleaseTest {
    @Test fun drainsSdkQueuesBeforeReleasingTheClientAndCheckingFileHandles() = runBlocking {
        val order = mutableListOf<String>()
        val release = NativeClientRelease({ order += "pause" }, { order += "release" }, { order += "drain" })
        release.close(); release.close()
        assertEquals(listOf("pause", "release", "drain"), order)
    }

    @Test fun failedPauseRetainsTheNativeClientAndSlotLockForRetry() = runBlocking {
        val root = Files.createTempDirectory("bipe-pause-retry-")
        val repository = AccountRepository(root, MemoryVault(), MemoryProtection())
        val lease = repository.provision()
        val owner = AccountLifetime(lease)
        var pauses = 0
        var releases = 0
        try {
            owner.own(NativeClientRelease(
                pause = { if (++pauses == 1) throw StorageFailure(StorageCode.IO_FAILURE) },
                release = { releases++ }, drain = {},
            ))
            assertTrue(owner.closeAsync().await() is AccountCloseResult.Blocked)
            assertEquals(0, releases)
            expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
            assertEquals(AccountCloseResult.Closed, owner.closeAsync().await())
            assertEquals(2, pauses)
            assertEquals(1, releases)
            repository.open(lease.slot).close()
        } finally { owner.closeAsync().await(); root.toFile().deleteRecursively() }
    }

    @Test fun failedFileDrainNeverUsesAnAlreadyReleasedNativeClientAgain() = runBlocking {
        var pauses = 0
        var releases = 0
        var drains = 0
        val release = NativeClientRelease({ pauses++ }, { releases++ }, {
            if (++drains == 1) throw StorageFailure(StorageCode.IO_FAILURE)
        })
        try { release.close(); fail("Expected retained drain failure") } catch (_: StorageFailure) { }
        release.close()
        assertEquals(1, pauses)
        assertEquals(1, releases)
        assertEquals(2, drains)
    }
}
