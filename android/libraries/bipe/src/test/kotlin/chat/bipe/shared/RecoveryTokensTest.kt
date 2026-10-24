// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeRecoveryTokenSync
import chat.bipe.shared.account.BipeRecoveryTokens
import chat.bipe.shared.account.BipeSyncedToken
import chat.bipe.shared.account.BipeSyncedTokenCodec
import chat.bipe.shared.account.BipeVaultAccount
import chat.bipe.shared.account.BipeVaultData
import chat.bipe.shared.account.BipeVaultStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.fail
import org.junit.Test

class RecoveryTokensTest {
    private class MemoryVault(var data: BipeVaultData = BipeVaultData()) : BipeVaultStore {
        override suspend fun read() = data
        override suspend fun update(transform: (BipeVaultData) -> BipeVaultData) {
            data = transform(data)
        }
    }

    /** Block Store in memory: entries by key, as the codec writes them. */
    private class MemorySync : BipeRecoveryTokenSync {
        val entries = linkedMapOf<String, ByteArray>()
        var failStore = false
        var failRead = false
        var failDelete = false
        var hang = false
        val deleted = mutableListOf<String?>()
        override val isAvailable = true

        override suspend fun store(userId: String, token: String) {
            if (hang) awaitCancellation()
            if (failStore) error("synthetic store failure")
            entries[BipeSyncedTokenCodec.key(userId)] = BipeSyncedTokenCodec.encode(userId, token)
        }

        override suspend fun all(): List<BipeSyncedToken> {
            if (hang) awaitCancellation()
            if (failRead) error("synthetic read failure")
            return entries.mapNotNull { (key, bytes) -> BipeSyncedTokenCodec.decode(key, bytes) }
        }

        override suspend fun delete(userId: String?) {
            if (failDelete) error("synthetic delete failure")
            deleted += userId
            entries.remove(BipeSyncedTokenCodec.key(userId))
        }
    }

    private val alice = "@alice:bipe.net.br"
    private val bob = "@bob:bipe.net.br"
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun tokens(vault: MemoryVault, sync: BipeRecoveryTokenSync, timeout: Long = 2_000) =
        BipeRecoveryTokens(vault, sync, scope, timeoutMillis = timeout)

    @Test
    fun newTokenSyncsOnceAndIsMarked() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        val sync = MemorySync()
        assertThat(tokens(vault, sync).syncPending()).isTrue()
        assertThat(sync.all()).containsExactly(BipeSyncedToken(alice, "token-a"))
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isTrue()
        // A synced token is not written again.
        sync.entries.clear()
        assertThat(tokens(vault, sync).syncPending()).isTrue()
        assertThat(sync.entries).isEmpty()
    }

    @Test
    fun failedWriteStaysUnsyncedAndRetries() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        val sync = MemorySync().apply { failStore = true }
        assertThat(tokens(vault, sync).syncPending()).isFalse()
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isFalse()
        assertThat(vault.data.accounts.getValue(alice).token).isEqualTo("token-a")
        sync.failStore = false
        assertThat(tokens(vault, sync).syncPending()).isTrue()
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isTrue()
    }

    @Test(timeout = 5_000)
    fun hangingStoreTimesOutWithoutMarking() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        val sync = MemorySync().apply { hang = true }
        assertThat(tokens(vault, sync, timeout = 50).syncPending()).isFalse()
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isFalse()
        // recovery-start still finds the vault's token while the store hangs.
        assertThat(tokens(vault, sync, timeout = 50).candidate(emptySet())?.token).isEqualTo("token-a")
    }

    @Test(timeout = 5_000)
    fun cancellingTheCallerCancelsTheSync() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        val started = CompletableDeferred<Unit>()
        val sync = object : BipeRecoveryTokenSync {
            override val isAvailable = true
            override suspend fun store(userId: String, token: String) {
                started.complete(Unit)
                awaitCancellation()
            }
            override suspend fun all() = emptyList<BipeSyncedToken>()
            override suspend fun delete(userId: String?) = Unit
        }
        val job = async { tokens(vault, sync, timeout = 60_000).syncPending() }
        started.await()
        job.cancel()
        try {
            job.await()
            fail("A cancelled sync returned a result")
        } catch (expected: kotlinx.coroutines.CancellationException) {
        }
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isFalse()
    }

    @Test
    fun aNewerTokenIsNotMarkedByAnOlderWrite() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        val sync = object : BipeRecoveryTokenSync {
            override val isAvailable = true
            override suspend fun store(userId: String, token: String) {
                // A recovery stores a new token while the old one is being written.
                vault.data = vault.data.withCredentials(alice, "pw2", "token-b")
            }
            override suspend fun all() = emptyList<BipeSyncedToken>()
            override suspend fun delete(userId: String?) = Unit
        }
        tokens(vault, sync).syncPending()
        assertThat(vault.data.accounts.getValue(alice).token).isEqualTo("token-b")
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isFalse()
    }

    @Test
    fun candidateSkipsSignedInAccountsAndPrefersTheVault() = runBlocking {
        val sync = MemorySync()
        sync.store(bob, "token-b")
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        assertThat(tokens(vault, sync).candidate(emptySet())).isEqualTo(BipeSyncedToken(alice, "token-a"))
        assertThat(tokens(vault, sync).candidate(setOf(alice))).isEqualTo(BipeSyncedToken(bob, "token-b"))
        assertThat(tokens(vault, sync).candidate(setOf(alice, bob))).isNull()
    }

    @Test
    fun aNewPhoneFindsAnAccountTokenBeforeTheOwnerlessOne() = runBlocking {
        val sync = MemorySync()
        sync.entries[BipeSyncedTokenCodec.LEGACY_KEY] = "token-old".encodeToByteArray()
        sync.store(bob, "token-b")
        assertThat(tokens(MemoryVault(), sync).candidate(emptySet())?.userId).isEqualTo(bob)
        assertThat(tokens(MemoryVault(), sync).candidate(setOf(bob))).isEqualTo(BipeSyncedToken(null, "token-old"))
        sync.failRead = true
        assertThat(tokens(MemoryVault(), sync).candidate(emptySet())).isNull()
        assertThat(tokens(MemoryVault(), BipeRecoveryTokenSync.None).candidate(emptySet())).isNull()
    }

    @Test
    fun rejectedTokenIsHiddenAtOnceAndDeletedFromTheStore() = runBlocking {
        val sync = MemorySync()
        sync.store(alice, "token-a")
        val vault = MemoryVault()
        val recovery = tokens(vault, sync)
        sync.failDelete = true
        recovery.revoke(alice, "token-a")
        yield()
        assertThat(recovery.candidate(emptySet())).isNull()
        assertThat(vault.data.revokedTokens).containsKey(alice)
        // The delete failed: it stays remembered and goes again on the next run.
        sync.failDelete = false
        assertThat(recovery.syncPending()).isTrue()
        assertThat(sync.entries).isEmpty()
        assertThat(vault.data.revokedTokens).isEmpty()
    }

    @Test
    fun revokingAnOldTokenKeepsTheNewerOne() = runBlocking {
        val sync = MemorySync()
        sync.store(alice, "token-new")
        val vault = MemoryVault(BipeVaultData().withRevokedToken(alice, "token-old"))
        assertThat(tokens(vault, sync).syncPending()).isTrue()
        assertThat(sync.deleted).isEmpty()
        assertThat(sync.all().single().token).isEqualTo("token-new")
        assertThat(vault.data.revokedTokens).isEmpty()
    }

    @Test
    fun revokeKeepsThePasswordAndForgetsTheTokenOnly() {
        val data = BipeVaultData().withCredentials(alice, "pw", "token-a").withRevokedToken(alice, "token-a")
        assertThat(data.accounts.getValue(alice).password).isEqualTo("pw")
        assertThat(data.accounts.getValue(alice).token).isNull()
        // A signed-out account kept only for its token goes away with it.
        val signedOut = BipeVaultData(accounts = mapOf(alice to BipeVaultAccount(token = "t", tokenSynced = true)))
        assertThat(signedOut.withRevokedToken(alice, "t").accounts).isEmpty()
        // Another token of the account is not touched.
        assertThat(BipeVaultData().withCredentials(alice, "pw", "token-b").withRevokedToken(alice, "token-a").accounts.getValue(alice).token)
            .isEqualTo("token-b")
    }

    @Test
    fun noSyncedStoreDoesNothing() = runBlocking {
        val vault = MemoryVault(BipeVaultData().withCredentials(alice, "pw", "token-a"))
        assertThat(tokens(vault, BipeRecoveryTokenSync.None).syncPending()).isTrue()
        assertThat(vault.data.accounts.getValue(alice).tokenSynced).isFalse()
        assertThat(tokens(vault, BipeRecoveryTokenSync.None).candidate(emptySet())?.token).isEqualTo("token-a")
    }

    @Test
    fun ownerlessEntryIsDeletedWhenRevoked() = runBlocking {
        val sync = MemorySync()
        sync.entries[BipeSyncedTokenCodec.LEGACY_KEY] = "token-old".encodeToByteArray()
        sync.store(bob, "token-old")
        val vault = MemoryVault()
        val recovery = tokens(vault, sync)
        recovery.revoke(null, "token-old")
        yield()
        assertThat(sync.deleted).containsExactly(null)
        // Bob's own entry is another key: it stays.
        assertThat(sync.all()).containsExactly(BipeSyncedToken(bob, "token-old"))
        assertThat(vault.data.revokedTokens).isEmpty()
    }

    @Test
    fun codecReadsTheForkFormatAndKeepsEachEntryToItsAccount() {
        val key = BipeSyncedTokenCodec.key(alice)
        // The fork's Play build (bipe-android libraries/bipeblockstore): prefix and 16 bytes of SHA-256 in hex.
        assertThat(key).isEqualTo("chat.bipe.recovery_token." + sha256Hex(alice).take(32))
        assertThat(key).doesNotContain("alice")
        val bytes = BipeSyncedTokenCodec.encode(alice, "token-a")
        assertThat(bytes.decodeToString()).isEqualTo("$alice\ntoken-a")
        assertThat(BipeSyncedTokenCodec.decode(key, bytes)).isEqualTo(BipeSyncedToken(alice, "token-a"))
        assertThat(BipeSyncedTokenCodec.decode("chat.bipe.recovery_token", "bare".encodeToByteArray())).isEqualTo(BipeSyncedToken(null, "bare"))
        // Under another account's key, under a foreign key, or damaged: never decoded.
        assertThat(BipeSyncedTokenCodec.decode(BipeSyncedTokenCodec.key(bob), bytes)).isNull()
        assertThat(BipeSyncedTokenCodec.decode("other", bytes)).isNull()
        assertThat(BipeSyncedTokenCodec.decode(key, "no-separator".encodeToByteArray())).isNull()
        assertThat(BipeSyncedTokenCodec.decode(key, "$alice\n".encodeToByteArray())).isNull()
        assertThat(BipeSyncedTokenCodec.decode("chat.bipe.recovery_token", ByteArray(0))).isNull()
        assertThat(BipeSyncedToken(alice, "token-a").toString()).doesNotContain("token-a")
        try {
            BipeSyncedTokenCodec.encode(alice, "x".repeat(2_000))
            fail("An entry over 1024 bytes was encoded")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            BipeSyncedTokenCodec.encode("@a\nb:bipe.net.br", "t")
            fail("A Matrix ID with a line break was encoded")
        } catch (expected: IllegalArgumentException) {
        }
    }

    private fun sha256Hex(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
