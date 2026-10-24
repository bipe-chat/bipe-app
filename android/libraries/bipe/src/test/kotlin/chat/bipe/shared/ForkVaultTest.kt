// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeForkVault
import chat.bipe.shared.account.BipeVaultAccount
import chat.bipe.shared.account.BipeVaultData
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic fork vaults (bipe-chat/bipe#603): the JSON the fork writes, mapped to this app's vault, as one line each. */
class ForkVaultTest {
    private fun line(data: BipeVaultData?): String = data?.let { vault ->
        val accounts = vault.accounts.entries.joinToString(",") { (id, a) -> "${id.substringBefore(':')}=${a.password}/${a.token}${if (a.tokenSynced) "+" else ""}" }
        val recovery = vault.pendingRecovery?.let { "${it.recoveryId}/${it.purpose}/${it.readyAt.toEpochMilli()}" }
        "accounts[$accounts] recovery=$recovery signup=${vault.pendingSignup?.key}/${vault.pendingSignup?.pin} inviters=${vault.pendingInviters}"
    } ?: "not the fork's"

    private val forks = mapOf(
        """{"accounts":{"@a:x":{"password":"pa"},"@b:x":{"password":"pb","local_token":"tb"},"@c:x":{}},
            "claim":{"recovery_id":"r1","claim_secret":"s","purpose":"pin_reset","ready_at":1700000000000},
            "pending_signup":{"key":"k","pin":"123456"},"pending_inviters":{"@b:x":"@a:x"},
            "signed_in_at":{"@a:x":1},"dismissed":["r0"],"inviter_greetings":{"@b:x":"@a:x"}}"""
            to "accounts[@a=pa/null,@b=pb/tb] recovery=r1/PIN_RESET/1700000000000 signup=k/123456 inviters={@b:x=@a:x}",
        """{"user_id":"@a:x","password":"pa","local_token":"ta"}"""
            to "accounts[@a=pa/ta] recovery=null signup=null/null inviters={}",
        """{"user_id":"@a:x","password":"pa","accounts":{"@b:x":{"password":"pb"}}}"""
            to "accounts[@a=pa/null,@b=pb/null] recovery=null signup=null/null inviters={}",
        """{"local_token":"orphan","claim":{"recovery_id":"r","claim_secret":"s","purpose":"other","ready_at":1}}"""
            to "accounts[] recovery=null signup=null/null inviters={}",
        "not json" to "not the fork's",
    )

    @Test
    fun forkShapes() {
        for ((json, expected) in forks) assertEquals(json, expected, line(BipeForkVault.parse(json)))
    }

    @Test
    fun whatThisAppHoldsWins() {
        val fork = BipeVaultData(accounts = mapOf("@a:x" to BipeVaultAccount("old"), "@b:x" to BipeVaultAccount("pb")))
        val merged = BipeForkVault.merge(BipeVaultData().withCredentials("@a:x", "new", "tn"), fork)
        assertEquals("accounts[@b=pb/null,@a=new/tn] recovery=null signup=null/null inviters={}", line(merged))
    }
}
