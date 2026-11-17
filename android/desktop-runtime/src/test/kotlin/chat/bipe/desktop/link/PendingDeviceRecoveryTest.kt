// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.link

import chat.bipe.desktop.storage.*
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Authored for CI; no live accounts, remote requests or native compilation. */
class PendingDeviceRecoveryTest {
    private val root = Files.createTempDirectory("bipe-pending-recovery-")
    private val vault = MemoryVault()
    private val repository = AccountRepository(root, vault, MemoryProtection())
    private val leases = mutableListOf<AccountLease>()
    @After fun cleanup() { leases.forEach { it.close() }; root.toFile().deleteRecursively() }

    @Test(timeout = 20_000) fun failedLogoutKeepsTheSessionKeyAndFilesForRetry() = runBlocking {
        val slot = pending()
        val recovery = recovery(slot) { lease -> lease.close(); throw IllegalStateException("synthetic-private-token") }
        recovery.run(PendingRecoveryAction.SIGN_OUT)
        assertTrue(recovery.phase.value is PendingRecoveryPhase.Failed)
        repository.open(slot).use { assertEquals("SYNTHETIC_DEVICE", it.pendingSession()!!.deviceId) }
        assertNotNull(vault.read(slot))
        assertTrue(repository.slots().single() is StoredSlot.Pending)
    }

    @Test(timeout = 20_000) fun confirmedLogoutIsJournaledThenOnlyThePendingSlotIsRemoved() = runBlocking {
        val slot = pending()
        val linked = linkedAccount()
        val recovery = recovery(slot) { lease ->
            assertEquals("SYNTHETIC_DEVICE", lease.pendingSession()!!.deviceId)
            lease.beginPendingDiscard(true)
            lease.close()
        }
        recovery.run(PendingRecoveryAction.SIGN_OUT)
        assertEquals(PendingRecoveryPhase.Done(true), recovery.phase.value)
        assertFalse(repository.slots().any { it.slot == slot })
        assertNull(vault.read(slot))
        repository.open(linked).use { assertEquals("LINKED_DEVICE", it.linkedSession().deviceId) }
    }

    @Test(timeout = 20_000) fun localForgetWithNoTokenMakesNoLogoutClaimOrNetworkCall() = runBlocking {
        val lease = repository.provision().also { leases += it }
        val slot = lease.slot
        Files.delete(lease.paths.drafts) // Missing pending-only directories do not block explicit local cleanup.
        lease.close()
        val recovery = recovery(slot) { fail("Local forget must not call logout") }
        recovery.run(PendingRecoveryAction.FORGET_LOCAL)
        assertEquals(PendingRecoveryPhase.Done(false), recovery.phase.value)
        assertTrue(repository.slots().isEmpty())
    }

    @Test(timeout = 20_000) fun aJournalSurvivesRestartAndLateCallbacksCannotReopenIt() = runBlocking {
        val slot = pending()
        repository.open(slot).use { lease ->
            lease.beginPendingDiscard(true)
            expect(StorageCode.STALE_OWNER) { lease.persistSession(session().copy(accessToken = "late")) }
            expect(StorageCode.LINK_INCOMPLETE) { repository.completeLink(lease, session()) }
        }
        val stored = repository.slots().single() as StoredSlot.Discarding
        assertTrue(stored.remoteEnded)
        val recovery = recovery(slot) { fail("Cleanup must not log in again") }
        recovery.run(PendingRecoveryAction.FINISH_CLEANUP)
        assertEquals(PendingRecoveryPhase.Done(true), recovery.phase.value)
    }

    @Test(timeout = 20_000) fun stalePendingSelectionCannotDiscardAnAlreadyLinkedSlot() = runBlocking {
        val linked = linkedAccount()
        val recovery = recovery(linked) { fail("Never log out a linked account in pending recovery") }
        recovery.run(PendingRecoveryAction.FORGET_LOCAL)
        assertTrue(recovery.phase.value is PendingRecoveryPhase.Failed)
        repository.open(linked).use { assertEquals("LINKED_DEVICE", it.linkedSession().deviceId) }
    }

    @Test(timeout = 20_000) fun failedKeyDeletionAndFileCleanupCanBeRetriedWithoutAnotherLogout() = runBlocking {
        val slot = pending()
        vault.failRemoval = true
        val recovery = recovery(slot) { lease -> lease.beginPendingDiscard(true); lease.close() }
        recovery.run(PendingRecoveryAction.SIGN_OUT)
        assertTrue(recovery.phase.value is PendingRecoveryPhase.Failed)
        assertTrue(repository.slots().single() is StoredSlot.Revoked)
        vault.failRemoval = false
        recovery.run(PendingRecoveryAction.FINISH_CLEANUP)
        assertEquals(PendingRecoveryPhase.Done(false), recovery.phase.value) // Key gone: do not invent a remote proof.
        assertTrue(repository.slots().isEmpty())
    }

    @Test(timeout = 20_000) fun workingIsSingleFlightAndCannotBeDismissed() = runBlocking {
        val slot = pending()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val recovery = recovery(slot) { lease ->
            calls++; entered.complete(Unit); release.await(); lease.beginPendingDiscard(true); lease.close()
        }
        val first = launch { recovery.run(PendingRecoveryAction.SIGN_OUT) }
        entered.await()
        recovery.run(PendingRecoveryAction.FORGET_LOCAL)
        assertFalse(recovery.close())
        assertEquals(1, calls)
        release.complete(Unit)
        first.join()
        assertEquals(PendingRecoveryPhase.Done(true), recovery.phase.value)
    }

    @Test(timeout = 20_000) fun failedNativeTeardownKeepsItsLeaseUntilExplicitCleanupRetry() = runBlocking {
        val slot = pending()
        var held: AccountLease? = null
        val recovery = recovery(slot) { lease ->
            held = lease.also { leases += it }
            lease.beginPendingDiscard(true)
            throw StorageFailure(StorageCode.IO_FAILURE)
        }
        recovery.run(PendingRecoveryAction.SIGN_OUT)
        assertTrue(recovery.phase.value is PendingRecoveryPhase.Failed)
        expect(StorageCode.SLOT_BUSY) { repository.openPendingRecovery(slot) }
        assertNotNull(vault.read(slot))
        held!!.close() // Represents the retained native owner finally completing teardown.
        recovery.run(PendingRecoveryAction.FINISH_CLEANUP)
        assertEquals(PendingRecoveryPhase.Done(true), recovery.phase.value)
    }

    private fun recovery(slot: AccountSlot, logout: suspend (AccountLease) -> Unit) =
        PendingDeviceRecovery(slot, repository, PendingLogout(logout), cleanup = {})
    private fun pending(): AccountSlot = repository.provision().also { leases += it }.let {
        it.persistSession(session()); it.close(); it.slot
    }
    private fun linkedAccount(): AccountSlot = repository.provision().also { leases += it }.let { lease ->
        for (name in SdkStoreLayout.databases) Files.write(lease.paths.data.resolve(name), ByteArray(4096))
        repository.completeLink(lease, session().copy(userId = "@linked:example.test", deviceId = "LINKED_DEVICE"))
        lease.close(); lease.slot
    }
}
