// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeRecoveryTokenSync
import chat.bipe.shared.account.BipeRecoveryTokens
import chat.bipe.shared.account.BipeSyncedToken
import chat.bipe.shared.account.BipeSyncedTokenCodec
import chat.bipe.shared.account.BipeVaultData
import chat.bipe.shared.account.BipeVaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

const val ALICE = "@alice:bipe.net.br"
const val BOB = "@bob:bipe.net.br"

/** What the synced store does on its next calls. */
enum class StoreMood { WORKS, FAILS, HANGS }

/**
 * One phone for the recovery token tests: the vault in memory and a Block Store in memory that keeps raw entries by key,
 * written through [BipeSyncedTokenCodec] like the real one.
 */
class RecoveryTokenWorld(vault: BipeVaultData = BipeVaultData(), timeoutMillis: Long = 2_000) {
    var data = vault
    val blockStore = linkedMapOf<String, ByteArray>()
    val deletedKeys = mutableListOf<String>()
    var writes = StoreMood.WORKS
    var reads = StoreMood.WORKS
    var deletes = StoreMood.WORKS

    /** Runs inside a write, before it lands: lets a test change the vault meanwhile. */
    var duringWrite: (() -> Unit)? = null

    private val store = object : BipeVaultStore {
        override suspend fun read() = data
        override suspend fun update(transform: (BipeVaultData) -> BipeVaultData) {
            data = transform(data)
        }
    }

    private val sync = object : BipeRecoveryTokenSync {
        override val isAvailable = true
        override suspend fun store(userId: String, token: String) {
            act(writes)
            duringWrite?.invoke()
            blockStore[BipeSyncedTokenCodec.key(userId)] = BipeSyncedTokenCodec.encode(userId, token)
        }
        override suspend fun all(): List<BipeSyncedToken> {
            act(reads)
            return blockStore.mapNotNull { BipeSyncedTokenCodec.decode(it.key, it.value) }
        }
        override suspend fun delete(userId: String?) {
            act(deletes)
            val key = BipeSyncedTokenCodec.key(userId)
            deletedKeys += key
            blockStore.remove(key)
        }
    }

    val tokens = BipeRecoveryTokens(store, sync, CoroutineScope(Dispatchers.Unconfined), timeoutMillis)
    val withoutSync = BipeRecoveryTokens(store, BipeRecoveryTokenSync.None, CoroutineScope(Dispatchers.Unconfined), timeoutMillis)

    fun put(userId: String?, token: String) {
        blockStore[BipeSyncedTokenCodec.key(userId)] =
            if (userId == null) token.encodeToByteArray() else BipeSyncedTokenCodec.encode(userId, token)
    }

    fun synced(): Set<BipeSyncedToken> = blockStore.mapNotNull { BipeSyncedTokenCodec.decode(it.key, it.value) }.toSet()

    fun account(userId: String) = data.accounts.getValue(userId)

    /** The whole state in one line, for the scenario table of RecoveryTokensTest. */
    fun describe(): String {
        val vault = data.accounts.entries.joinToString(",") { (id, a) -> "${id.take(4)}:${a.token}${if (a.tokenSynced) "+" else ""}" }
        val store = synced().joinToString(",") { "${it.userId?.take(4)}:${it.token}" }
        return "vault[$vault] store[$store] revoked[${data.revokedTokens.keys.joinToString(",") { it.take(4) }}]"
    }

    private suspend fun act(mood: StoreMood) {
        when (mood) {
            StoreMood.WORKS -> Unit
            StoreMood.FAILS -> throw IllegalStateException("synthetic Block Store failure")
            StoreMood.HANGS -> awaitCancellation()
        }
    }
}

fun recoveryWorld(vault: BipeVaultData = BipeVaultData(), timeoutMillis: Long = 2_000, body: suspend RecoveryTokenWorld.() -> Unit) =
    runBlocking { RecoveryTokenWorld(vault, timeoutMillis).body() }
