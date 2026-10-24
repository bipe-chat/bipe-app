// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import chat.bipe.shared.api.BipeRecoveryPurpose
import chat.bipe.shared.api.BipeRecoveryStarted
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fork's account vault, for the switch to the fork's application ID (bipe-chat/bipe#603, docs/client-switch-plan.md
 * step 3). The fork keeps it in the same file as this app ([BipeAccountVault], `noBackupFilesDir/bipe-account`) under
 * its own Android Keystore key ([KEY_ALIAS]) and its own cipher wrapper, so [BipeAccountVault] keeps that file aside as
 * [FILE_NAME] instead of reading it as its own.
 *
 * The JSON inside follows the fork's `BipeVaultData` (bipe-android `libraries/bipe/.../account/BipeAccountVault.kt`,
 * Bipe code). The cipher wrapper is not Bipe code: its layout is taken from the stored file on the rehearsal phone
 * (plan section 4 step 3), not from the fork's sources, and lands with that evidence.
 */
object BipeForkVault {
    /** Where [BipeAccountVault] moves the fork's file. */
    const val FILE_NAME = "bipe-account.fork"

    /** The fork's Android Keystore alias for the vault key. */
    const val KEY_ALIAS = "bipe.SECRET_KEY_ALIAS_ACCOUNT"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Account(
        @SerialName("password") val password: String? = null,
        @SerialName("local_token") val localToken: String? = null,
    )

    @Serializable
    private data class Claim(
        @SerialName("recovery_id") val recoveryId: String,
        @SerialName("claim_secret") val claimSecret: String,
        @SerialName("purpose") val purposeId: String,
        @SerialName("ready_at") val readyAtMillis: Long,
    )

    @Serializable
    private data class Data(
        @SerialName("accounts") val accounts: Map<String, Account> = emptyMap(),
        @SerialName("user_id") val legacyUserId: String? = null,
        @SerialName("password") val legacyPassword: String? = null,
        @SerialName("local_token") val legacyLocalToken: String? = null,
        @SerialName("claim") val claim: Claim? = null,
        @SerialName("pending_signup") val pendingSignup: BipePendingSignup? = null,
        @SerialName("pending_inviters") val pendingInviters: Map<String, String> = emptyMap(),
    )

    /**
     * The fork's decrypted vault JSON as this app's [BipeVaultData], or `null` when it is not the fork's JSON.
     *
     * - Hidden passwords keep their account and order (the last is the newest).
     * - A token kept on the phone (`local_token`) comes over unsynced; the fork's synced tokens are already in Block
     *   Store in the same format ([BipeSyncedTokenCodec]).
     * - The one account from before bipe-chat/bipe#265 (`user_id`, `password`) becomes an account entry. A bare
     *   `local_token` without its account cannot be used without the store's Matrix ID and is left out.
     * - A waiting e-mail recovery, a pending sign-up and pending inviters come over as they are.
     */
    fun parse(text: String): BipeVaultData? {
        val fork = runCatching { json.decodeFromString(Data.serializer(), text) }.getOrNull() ?: return null
        val accounts = LinkedHashMap<String, BipeVaultAccount>()
        fork.legacyUserId?.let { userId ->
            if (fork.legacyPassword != null || fork.legacyLocalToken != null) {
                accounts[userId] = BipeVaultAccount(fork.legacyPassword, fork.legacyLocalToken)
            }
        }
        for ((userId, account) in fork.accounts) {
            if (account.password == null && account.localToken == null) continue
            accounts.remove(userId)
            accounts[userId] = BipeVaultAccount(account.password, account.localToken)
        }
        return BipeVaultData(
            accounts = accounts,
            pendingSignup = fork.pendingSignup,
            pendingInviters = fork.pendingInviters,
            pendingRecovery = fork.claim?.toStarted(),
        )
    }

    /**
     * Adds the fork's data to this app's vault. What this app already holds wins: an account signed in again after the
     * switch keeps its new secrets, and the fork's accounts come before it in the order.
     */
    fun merge(current: BipeVaultData, fork: BipeVaultData): BipeVaultData = current.copy(
        accounts = fork.accounts.filterKeys { it !in current.accounts } + current.accounts,
        pendingSignup = current.pendingSignup ?: fork.pendingSignup,
        pendingInviters = fork.pendingInviters + current.pendingInviters,
        pendingRecovery = current.pendingRecovery ?: fork.pendingRecovery,
    )

    private fun Claim.toStarted(): BipeRecoveryStarted? {
        val purpose = BipeRecoveryPurpose.entries.firstOrNull { it.wireId == purposeId } ?: return null
        return BipeRecoveryStarted(recoveryId, claimSecret, purpose, Instant.ofEpochMilli(readyAtMillis))
    }

    private val BipeRecoveryPurpose.wireId: String
        get() = when (this) {
            BipeRecoveryPurpose.ACCESS -> "access"
            BipeRecoveryPurpose.PIN_RESET -> "pin_reset"
        }
}
