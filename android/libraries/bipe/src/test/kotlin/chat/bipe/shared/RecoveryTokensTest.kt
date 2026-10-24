// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeVaultAccount
import chat.bipe.shared.account.BipeVaultData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ownership, retry, revocation and cancellation of the Android recovery token (ADR 0059, ADR 0150), as a table: each
 * scenario runs on a fresh phone and ends in one line of state ([RecoveryTokenWorld.describe]) plus what it returned.
 */
class RecoveryTokensTest {
    private class Scenario(
        val name: String,
        val vault: BipeVaultData,
        val expected: String,
        val timeoutMillis: Long = 2_000,
        val run: suspend RecoveryTokenWorld.() -> Any?,
    )

    private val signedUp = BipeVaultData().withCredentials(ALICE, "pw", "token-a")

    private val scenarios = listOf(
        Scenario("a failed write stays unsynced, then goes out once", signedUp, "[false, true, true] vault[@ali:token-a+] store[@ali:token-a] revoked[]") {
            writes = StoreMood.FAILS
            val first = tokens.syncPending()
            writes = StoreMood.WORKS
            listOf(first, tokens.syncPending(), tokens.syncPending())
        },
        Scenario("a hanging store hits the time limit; the vault still answers", signedUp, "[false, token-a, null] vault[@ali:token-a] store[] revoked[]", timeoutMillis = 50) {
            writes = StoreMood.HANGS
            reads = StoreMood.HANGS
            listOf(tokens.syncPending(), tokens.candidate(emptySet())?.token, tokens.candidate(setOf(ALICE)))
        },
        Scenario("cancelling the caller cancels the write and marks nothing", signedUp, "cancelled vault[@ali:token-a] store[] revoked[]", timeoutMillis = 60_000) {
            writes = StoreMood.HANGS
            coroutineScope {
                val run = async(start = CoroutineStart.UNDISPATCHED) { tokens.syncPending() }
                run.cancel()
                if (runCatching { run.await() }.exceptionOrNull() is CancellationException) "cancelled" else "not cancelled"
            }
        },
        Scenario("an old token's write never marks the new token", signedUp, "null vault[@ali:token-b] store[@ali:token-a] revoked[]") {
            duringWrite = { data = data.withCredentials(ALICE, "pw2", "token-b") }
            tokens.syncPending()
            null
        },
        Scenario("recovery-start offers only accounts that are not here, ownerless last", signedUp, "[token-a, token-b, token-old, null, null] vault[@ali:token-a] store[@bob:token-b,null:token-old] revoked[]") {
            put(BOB, "token-b")
            put(null, "token-old")
            val offers = listOf(emptySet(), setOf(ALICE), setOf(ALICE, BOB)).map { tokens.candidate(it)?.token }
            reads = StoreMood.FAILS
            offers + tokens.candidate(setOf(ALICE))?.token + withoutSync.candidate(setOf(ALICE))?.token
        },
        Scenario("a rejected token hides at once and leaves the store on the next run", BipeVaultData(), "[null, true] vault[] store[] revoked[]") {
            put(ALICE, "token-a")
            deletes = StoreMood.FAILS
            tokens.revoke(ALICE, "token-a")
            val offer = tokens.candidate(emptySet())
            deletes = StoreMood.WORKS
            listOf(offer, tokens.syncPending())
        },
        Scenario(
            "revoking deletes only the matching entry",
            BipeVaultData().withRevokedToken(ALICE, "token-old").withRevokedToken(null, "shared"),
            "[chat.bipe.recovery_token] vault[] store[@ali:token-new,@bob:shared] revoked[]",
        ) {
            put(ALICE, "token-new")
            put(BOB, "shared")
            put(null, "shared")
            tokens.syncPending()
            deletedKeys
        },
        Scenario("the vault drops the rejected token and keeps the password", signedUp, "[pw, true, token-a] vault[@ali:null] store[] revoked[@ali]") {
            val signedOut = BipeVaultData(accounts = mapOf(BOB to BipeVaultAccount(token = "t", tokenSynced = true)))
            val other = data.withRevokedToken(ALICE, "other").accounts.getValue(ALICE).token
            data = data.withRevokedToken(ALICE, "token-a")
            listOf(account(ALICE).password, signedOut.withRevokedToken(BOB, "t").accounts.isEmpty(), other)
        },
        Scenario("a build without the synced store keeps the token on the phone", signedUp, "[true, token-a] vault[@ali:token-a] store[] revoked[]") {
            listOf(withoutSync.syncPending(), withoutSync.candidate(emptySet())?.token)
        },
    )

    @Test(timeout = 10_000)
    fun scenarios() {
        for (scenario in scenarios) {
            var result: Any? = null
            var state = ""
            recoveryWorld(scenario.vault, scenario.timeoutMillis) {
                result = scenario.run(this)
                state = describe()
            }
            assertEquals(scenario.name, scenario.expected, "$result $state")
        }
    }
}
