// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.storage

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

class AccountRepositoryTest {
    private val root = Files.createTempDirectory("bipe-slot-test-")
    private val vault = MemoryVault()
    private val protection = MemoryProtection()
    private val repository = AccountRepository(root, vault, protection)
    private val leases = mutableListOf<AccountLease>()

    @After fun cleanup() {
        leases.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    @Test fun pendingTokenDoesNotBecomeALinkedAccountAfterRestart() {
        val lease = provision()
        lease.persistSession(session())
        lease.close()
        val reopened = open(lease.slot)
        expect(StorageCode.LINK_INCOMPLETE) { reopened.linkedSession() }
        assertEquals(session().deviceId, reopened.savedSession()!!.deviceId)
        assertTrue(repository.slots().single() is StoredSlot.Pending)
    }

    @Test fun linkedRecordKeepsDeviceAndRefreshedCredentialsInTheOriginalSlot() {
        val lease = provision()
        materializeStore(lease)
        repository.completeLink(lease, session())
        lease.persistSession(session().copy(accessToken = "synthetic-refreshed", refreshToken = "synthetic-refresh"))
        lease.close()
        val reopened = open(lease.slot)
        assertEquals(lease.paths, reopened.paths)
        assertEquals("SYNTHETIC_DEVICE", reopened.linkedSession().deviceId)
        assertEquals("synthetic-refreshed", reopened.linkedSession().accessToken)
        assertEquals(1, vault.created)
        assertTrue(repository.slots().single() is StoredSlot.Linked)
    }

    @Test fun missingKeyNeverCreatesAReplacementOrChangesTheStore() {
        val lease = provision()
        materializeStore(lease)
        repository.completeLink(lease, session())
        val database = lease.paths.data.resolve(SdkStoreLayout.databases.first())
        val before = Files.readAllBytes(database)
        lease.close()
        vault.remove(lease.slot)
        expect(StorageCode.MISSING_KEY) { repository.open(lease.slot) }
        assertArrayEquals(before, Files.readAllBytes(database))
        assertEquals(1, vault.created)
    }

    @Test fun missingStoreNeverRecreatesAnEmptyLinkedDevice() {
        val lease = provision()
        materializeStore(lease)
        repository.completeLink(lease, session())
        lease.close()
        val database = lease.paths.data.resolve(SdkStoreLayout.databases.first())
        Files.delete(database)
        expect(StorageCode.MISSING_STORE) { repository.open(lease.slot) }
        assertFalse(Files.exists(database))
        assertEquals(1, vault.created)
    }

    @Test fun theSameSlotCannotHaveTwoOwners() {
        val lease = provision()
        expect(StorageCode.SLOT_BUSY) { repository.open(lease.slot) }
        lease.close()
        open(lease.slot)
    }

    @Test fun accountKeysPathsAndRecordsCannotBeInterchanged() {
        val first = provision()
        val second = provision()
        assertNotEquals(first.paths, second.paths)
        val a = vault.read(first.slot)!!
        val b = vault.read(second.slot)!!
        try { assertFalse(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
        first.persistSession(session())
        second.persistSession(session().copy(userId = "@second:example.test", deviceId = "OTHER_DEVICE"))
        first.close()
        second.close()
        Files.copy(first.paths.root.resolve(AccountRepository.RECORD), second.paths.root.resolve(AccountRepository.RECORD),
                   java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        expect(StorageCode.CORRUPT_RECORD) { repository.open(second.slot) }
        assertEquals("SYNTHETIC_DEVICE", open(first.slot).savedSession()!!.deviceId)
    }

    @Test fun evenADecryptableRecordMustBelongToItsSlot() {
        val lease = provision()
        val different = AccountSlot.create()
        val bytes = SessionCodec.encode(different, StoredRecord.Pending(session()))
        val protected = lease.useStoreKey { protection.protect(bytes, it) }
        bytes.fill(0)
        Files.write(lease.paths.root.resolve(AccountRepository.RECORD), protected)
        lease.close()
        expect(StorageCode.CORRUPT_RECORD) { repository.open(lease.slot) }
    }

    @Test fun corruptInputErrorsDoNotQuoteTheCredentialOrParserCause() {
        val failure = expect(StorageCode.CORRUPT_RECORD) {
            SessionCodec.decode(AccountSlot.create(), "{\"secret\":\"synthetic-private-token".toByteArray())
        }
        assertFalse(failure.message!!.contains("synthetic-private-token"))
        assertNull(failure.cause)
    }

    @Test fun identityChangesCannotOverwriteTheOriginalRecord() {
        val lease = provision()
        lease.persistSession(session())
        for (next in listOf(session().copy(userId = "@other:example.test"), session().copy(deviceId = "OTHER"),
                            session().copy(homeserverUrl = "https://other.example.test"))) {
            expect(StorageCode.IDENTITY_MISMATCH) { lease.persistSession(next) }
        }
        assertEquals("SYNTHETIC_DEVICE", lease.savedSession()!!.deviceId)
    }

    @Test fun retiredCallbacksCannotSaveEvenAfterANewOwnerOpensTheSlot() {
        val old = provision()
        old.persistSession(session())
        old.retire()
        expect(StorageCode.STALE_OWNER) { old.persistSession(session().copy(accessToken = "late")) }
        old.close()
        val current = open(old.slot)
        expect(StorageCode.STALE_OWNER) { old.persistSession(session().copy(accessToken = "later")) }
        assertEquals("synthetic-access", current.savedSession()!!.accessToken)
    }

    @Test fun tombstoneBlocksRestoreEvenIfCredentialDeletionFails() {
        val lease = provision()
        lease.persistSession(session())
        vault.failRemoval = true
        assertTrue(lease.revoke() is RevocationResult.CleanupRequired)
        expect(StorageCode.REVOKED) { lease.persistSession(session()) }
        lease.close()
        expect(StorageCode.REVOKED) { repository.open(lease.slot) }
        expect(StorageCode.STALE_OWNER) { repository.removeRevokedFiles(lease.slot) }
        assertTrue(Files.exists(lease.paths.root))
    }

    @Test fun keyRemovalStillBlocksRestoreIfTombstoneCannotBeWritten() {
        val lease = provision()
        // A directory cannot be atomically replaced by the marker file.
        Files.createDirectory(lease.paths.root.resolve(AccountRepository.REVOKED))
        assertTrue(lease.revoke() is RevocationResult.CleanupRequired)
        assertNull(vault.read(lease.slot))
        Files.delete(lease.paths.root.resolve(AccountRepository.REVOKED))
        lease.close()
        expect(StorageCode.MISSING_KEY) { repository.open(lease.slot) }
    }

    @Test fun revokedFilesCannotBeDeletedUntilTheOwnerReleasesThem() {
        val lease = provision()
        assertEquals(RevocationResult.Complete, lease.revoke())
        expect(StorageCode.SLOT_BUSY) { repository.removeRevokedFiles(lease.slot) }
        lease.close()
        repository.removeRevokedFiles(lease.slot)
        assertFalse(Files.exists(lease.paths.root))
    }

    @Test fun duplicateLinkCannotReplaceAnAlreadyLinkedAccount() {
        val first = provision()
        val second = provision()
        materializeStore(first)
        materializeStore(second)
        repository.completeLink(first, session())
        expect(StorageCode.DUPLICATE_ACCOUNT) { repository.completeLink(second, session().copy(deviceId = "REDUNDANT")) }
        assertEquals("SYNTHETIC_DEVICE", first.linkedSession().deviceId)
        expect(StorageCode.LINK_INCOMPLETE) { second.linkedSession() }
    }

    @Test fun slotParserRejectsPathsAndNonCanonicalIdentifiers() {
        for (value in listOf("../account", "", UUID.randomUUID().toString().uppercase(), "0-0-0-0-0")) {
            expect(StorageCode.INVALID_SLOT) { AccountSlot.parse(value) }
        }
    }

    private fun provision() = repository.provision().also { leases += it }
    private fun open(slot: AccountSlot) = repository.open(slot).also { leases += it }

    private fun materializeStore(lease: AccountLease) {
        // Unit-test fixture for pre-open guards, deliberately not a native SDK database.
        for (name in SdkStoreLayout.databases) Files.write(lease.paths.data.resolve(name), ByteArray(4096) { 42 })
    }
}

internal fun session() = Session("synthetic-access", null, "@synthetic:example.test", "SYNTHETIC_DEVICE",
                                "http://127.0.0.1:9", null, SlidingSyncVersion.NATIVE)

internal fun expect(code: StorageCode, operation: () -> Unit): StorageFailure {
    try { operation() } catch (failure: StorageFailure) { assertEquals(code, failure.code); return failure }
    throw AssertionError("Expected $code")
}

internal class MemoryVault : StoreKeyVault {
    private val keys = mutableMapOf<AccountSlot, ByteArray>()
    var created = 0
    var failRemoval = false
    override fun read(slot: AccountSlot) = keys[slot]?.copyOf()
    override fun create(slot: AccountSlot, key: ByteArray) {
        check(slot !in keys)
        keys[slot] = key.copyOf()
        created++
    }
    override fun remove(slot: AccountSlot) {
        if (failRemoval) throw StorageFailure(StorageCode.NATIVE_FAILURE)
        keys.remove(slot)?.fill(0)
    }
}

/** Opaque handles to in-memory test data. This fake provides no encryption and is never a product adapter. */
internal class MemoryProtection : UserDataProtection {
    private class Value(val entropy: ByteArray, val clear: ByteArray)
    private val values = mutableMapOf<String, Value>()
    override fun protect(clear: ByteArray, entropy: ByteArray): ByteArray {
        val handle = UUID.randomUUID().toString()
        values[handle] = Value(entropy.copyOf(), clear.copyOf())
        return handle.toByteArray()
    }
    override fun unprotect(sealed: ByteArray, entropy: ByteArray): ByteArray {
        val value = values[sealed.toString(Charsets.UTF_8)] ?: throw StorageFailure(StorageCode.CORRUPT_RECORD)
        if (!value.entropy.contentEquals(entropy)) throw StorageFailure(StorageCode.CORRUPT_RECORD)
        return value.clear.copyOf()
    }
}
