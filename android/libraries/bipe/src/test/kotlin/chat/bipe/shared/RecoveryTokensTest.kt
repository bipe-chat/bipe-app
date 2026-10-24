// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeSyncedToken
import chat.bipe.shared.account.BipeSyncedTokenCodec
import chat.bipe.shared.account.BipeVaultAccount
import chat.bipe.shared.account.BipeVaultData
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Test

/** Ownership, retry, revocation and cancellation of the Android recovery token (ADR 0059, ADR 0150). */
class RecoveryTokensTest {
    private val signedUp = BipeVaultData().withCredentials(ALICE, "pw", "token-a")

    @Test
    fun aNewTokenGoesOutOnceAndRetriesAfterAFailure() = recoveryWorld(signedUp) {
        writes = StoreMood.FAILS
        assertThat(tokens.syncPending()).isFalse()
        assertThat(account(ALICE).tokenSynced).isFalse()
        assertThat(account(ALICE).token).isEqualTo("token-a")

        writes = StoreMood.WORKS
        assertThat(tokens.syncPending()).isTrue()
        assertThat(synced()).containsExactly(BipeSyncedToken(ALICE, "token-a"))
        assertThat(account(ALICE).tokenSynced).isTrue()

        blockStore.clear()
        tokens.syncPending()
        assertThat(blockStore).isEmpty()
    }

    @Test(timeout = 5_000)
    fun aHangingStoreHitsTheTimeLimitAndTheVaultStillAnswers() = recoveryWorld(signedUp, timeoutMillis = 50) {
        writes = StoreMood.HANGS
        reads = StoreMood.HANGS
        assertThat(tokens.syncPending()).isFalse()
        assertThat(account(ALICE).tokenSynced).isFalse()
        assertThat(tokens.candidate(emptySet())?.token).isEqualTo("token-a")
        assertThat(tokens.candidate(setOf(ALICE))).isNull()
    }

    @Test(timeout = 5_000)
    fun cancellingTheCallerStopsTheWriteWithoutMarkingIt() = recoveryWorld(signedUp, timeoutMillis = 60_000) {
        writes = StoreMood.HANGS
        val outcome = coroutineScope {
            val run = async(start = CoroutineStart.UNDISPATCHED) { tokens.syncPending() }
            run.cancel()
            runCatching { run.await() }
        }
        assertThat(outcome.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(account(ALICE).tokenSynced).isFalse()
    }

    @Test
    fun aWriteOfAnOldTokenNeverMarksTheNewOne() = recoveryWorld(signedUp) {
        duringWrite = { data = data.withCredentials(ALICE, "pw2", "token-b") }
        tokens.syncPending()
        assertThat(account(ALICE).token).isEqualTo("token-b")
        assertThat(account(ALICE).tokenSynced).isFalse()
    }

    @Test
    fun recoveryStartOffersOnlyAccountsThatAreNotHere() = recoveryWorld(signedUp) {
        put(BOB, "token-b")
        put(null, "token-old")
        val offers = listOf(emptySet(), setOf(ALICE), setOf(ALICE, BOB)).map { tokens.candidate(it) }
        assertThat(offers).containsExactly(
            BipeSyncedToken(ALICE, "token-a"),
            BipeSyncedToken(BOB, "token-b"),
            BipeSyncedToken(null, "token-old"),
        ).inOrder()

        reads = StoreMood.FAILS
        assertThat(tokens.candidate(setOf(ALICE))).isNull()
        assertThat(withoutSync.candidate(setOf(ALICE))).isNull()
        assertThat(withoutSync.syncPending()).isTrue()
    }

    @Test
    fun aRejectedTokenHidesAtOnceAndLeavesTheStoreLater() = recoveryWorld {
        put(ALICE, "token-a")
        deletes = StoreMood.FAILS
        tokens.revoke(ALICE, "token-a")
        assertThat(tokens.candidate(emptySet())).isNull()
        assertThat(data.revokedTokens.keys).containsExactly(ALICE)

        deletes = StoreMood.WORKS
        assertThat(tokens.syncPending()).isTrue()
        assertThat(blockStore).isEmpty()
        assertThat(data.revokedTokens).isEmpty()
    }

    @Test
    fun revokingKeepsEveryOtherEntry() = recoveryWorld(BipeVaultData().withRevokedToken(ALICE, "token-old").withRevokedToken(null, "shared")) {
        put(ALICE, "token-new")
        put(BOB, "shared")
        put(null, "shared")
        assertThat(tokens.syncPending()).isTrue()
        assertThat(deletedKeys).containsExactly(BipeSyncedTokenCodec.LEGACY_KEY)
        assertThat(synced()).containsExactly(BipeSyncedToken(ALICE, "token-new"), BipeSyncedToken(BOB, "shared"))
        assertThat(data.revokedTokens).isEmpty()
    }

    @Test
    fun theVaultDropsOnlyTheRejectedToken() {
        val kept = signedUp.withRevokedToken(ALICE, "token-a").accounts.getValue(ALICE)
        assertThat(kept.password).isEqualTo("pw")
        assertThat(kept.token).isNull()
        val signedOut = BipeVaultData(accounts = mapOf(ALICE to BipeVaultAccount(token = "t", tokenSynced = true)))
        assertThat(signedOut.withRevokedToken(ALICE, "t").accounts).isEmpty()
        assertThat(signedUp.withRevokedToken(ALICE, "other").accounts.getValue(ALICE).token).isEqualTo("token-a")
    }
}
