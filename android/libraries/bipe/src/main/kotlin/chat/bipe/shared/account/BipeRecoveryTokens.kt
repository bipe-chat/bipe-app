// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * The recovery token on Android (ADR 0057, ADR 0059): [BipeAccountVault] keeps it on this phone, and the synced store
 * (Block Store in the Play build, ADR 0150) keeps it in the Google account, so a new phone finds it.
 *
 * - Writes to the synced store never block a sign-up or a recovery. A token that did not reach the store stays marked
 *   unsynced in the vault and goes out on the next [syncSoon] (each app start and each new account).
 * - Each entry belongs to one Matrix ID ([BipeSyncedTokenCodec]); an entry never opens another account.
 * - A token the API rejected is remembered by its fingerprint until the synced store has dropped it, so recovery-start
 *   never offers it again, even when the delete fails.
 * - The synced store may be slow or missing: every call has a time limit, and a failed read falls back to the vault.
 */
class BipeRecoveryTokens(
    private val vault: BipeVaultStore,
    private val sync: BipeRecoveryTokenSync,
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val running = Mutex()

    /** Runs [syncPending] in [scope]. */
    fun syncSoon(): Job = scope.launch { syncPending() }

    /** False when this build has no synced store. */
    val isAvailable: Boolean get() = sync.isAvailable

    /** True when the synced store backs tokens up off this phone (spec recovery-reminder syncOff). */
    suspend fun isBackedUp(): Boolean = sync.isBackedUp()

    /**
     * Writes every unsynced token to the synced store and removes the rejected ones. True when nothing is left to do;
     * false when a step failed and waits for the next run.
     */
    suspend fun syncPending(): Boolean = running.withLock {
        if (!sync.isAvailable) return@withLock true
        val data = vault.read()
        var done = true
        for ((userId, account) in data.accounts) {
            val token = account.token ?: continue
            if (account.tokenSynced) continue
            if (attempt { sync.store(userId, token) }) {
                vault.update { it.withTokenSynced(userId, token) }
            } else {
                done = false
            }
        }
        if (data.revokedTokens.isNotEmpty()) {
            val synced = attemptValue { sync.all() }
            if (synced == null) return@withLock false
            for ((owner, fingerprint) in data.revokedTokens) {
                val userId = owner.takeIf { it != BipeVaultData.LEGACY_OWNER }
                val entry = synced.firstOrNull { it.userId == userId }
                // Delete only the rejected token: a newer token of the same account stays.
                val gone = entry == null || BipeSyncedTokenCodec.fingerprint(entry.token) != fingerprint || attempt { sync.delete(userId) }
                if (gone) vault.update { it.withoutRevoked(owner, fingerprint) } else done = false
            }
        }
        done
    }

    /**
     * recovery-start, action appear: the token of an account that is not signed in on this phone ([signedIn]), or
     * `null`. The vault's newest account first, then an account's entry in the synced store, then the entry from before
     * bipe-chat/bipe#265.
     */
    suspend fun candidate(signedIn: Set<String>): BipeSyncedToken? {
        val data = vault.read()
        fun offered(userId: String?, token: String) = (userId == null || userId !in signedIn) &&
            data.revokedTokens[userId ?: BipeVaultData.LEGACY_OWNER] != BipeSyncedTokenCodec.fingerprint(token)
        data.accounts.entries.lastOrNull { (userId, account) -> account.token?.let { offered(userId, it) } == true }?.let { (userId, account) ->
            return BipeSyncedToken(userId, account.token!!)
        }
        if (!sync.isAvailable) return null
        val synced = attemptValue { sync.all() } ?: return null
        return synced.filter { offered(it.userId, it.token) }.sortedBy { it.userId == null }.firstOrNull()
    }

    /**
     * The API rejected [token] (`recovery_invalid`), or a recovery replaced it: the vault drops it and remembers it at
     * once, so the next [candidate] skips it; the synced store drops it in the background. [userId] `null` is the entry
     * from before bipe-chat/bipe#265.
     */
    suspend fun revoke(userId: String?, token: String) {
        vault.update { it.withRevokedToken(userId, token) }
        syncSoon()
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = attemptValue { block(); true } ?: false

    /** `null` when [block] fails or takes longer than the time limit. Cancellation of the caller still cancels. */
    private suspend fun <T> attemptValue(block: suspend () -> T): T? = try {
        withTimeout(timeoutMillis) { block() }
    } catch (error: TimeoutCancellationException) {
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        null
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
