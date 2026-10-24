// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import java.security.MessageDigest

/**
 * A recovery token in the synced store, with the account it opens (ADR 0059). [userId] is `null` for the one entry the
 * fork wrote before bipe-chat/bipe#265, whose account the store does not know; the token still works for a recovery.
 */
data class BipeSyncedToken(val userId: String?, val token: String) {
    override fun toString(): String = "BipeSyncedToken($userId)"
}

/**
 * The synced store of recovery tokens: Block Store in the Android Play build (ADR 0150). The build without Google Play
 * services uses [None]. Every call throws when the store fails; [BipeRecoveryTokens] keeps the work and tries again.
 */
interface BipeRecoveryTokenSync {
    /** False when this build has no synced store. */
    val isAvailable: Boolean

    /** Writes the token of [userId], in place of an older one. */
    suspend fun store(userId: String, token: String)

    /** Every Bipe recovery token in the store. Entries that are not Bipe's, or are damaged, are left out. */
    suspend fun all(): List<BipeSyncedToken>

    /** Removes the token of [userId] (`null`: the entry from before bipe-chat/bipe#265). Nothing happens when there is none. */
    suspend fun delete(userId: String?)

    /** No synced store: the token stays in [BipeAccountVault], on this phone only. */
    object None : BipeRecoveryTokenSync {
        override val isAvailable = false
        override suspend fun store(userId: String, token: String) = Unit
        override suspend fun all(): List<BipeSyncedToken> = emptyList()
        override suspend fun delete(userId: String?) = Unit
    }
}

/**
 * The format of the synced entries, the same as the fork's Play build writes (bipe-android `libraries/bipeblockstore`),
 * so the app reads the fork's entries after the switch to the fork's application ID (docs/client-switch-plan.md step 3).
 *
 * - One entry per account (ADR 0086). The key is [KEY_PREFIX] and the first 16 bytes of the SHA-256 of the Matrix ID in
 *   hex: a Matrix ID has characters a Block Store key may not, and the key list does not name the account.
 * - The value is the Matrix ID, a line break and the token (a Matrix ID never holds a line break).
 * - [LEGACY_KEY] holds a bare token from before bipe-chat/bipe#265. Read only; never written.
 */
object BipeSyncedTokenCodec {
    /** Block Store keeps at most 1024 bytes per entry. */
    const val MAX_BYTES = 1024
    const val KEY_PREFIX = "chat.bipe.recovery_token."
    const val LEGACY_KEY = "chat.bipe.recovery_token"
    private const val SEPARATOR = "\n"

    fun key(userId: String?): String = if (userId == null) LEGACY_KEY else KEY_PREFIX + sha256(userId).take(32)

    /** Throws [IllegalArgumentException] for a Matrix ID with a line break or an entry over [MAX_BYTES]. */
    fun encode(userId: String, token: String): ByteArray {
        require(userId.isNotEmpty() && SEPARATOR !in userId) { "invalid Matrix ID" }
        val bytes = "$userId$SEPARATOR$token".encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "recovery token entry too large" }
        return bytes
    }

    /**
     * `null` for a key that is not Bipe's, an empty or damaged value, or a value stored under another account's key: an
     * entry only ever opens the account its key names.
     */
    fun decode(key: String, bytes: ByteArray): BipeSyncedToken? {
        val value = bytes.decodeToString()
        if (key == LEGACY_KEY) return value.takeIf { it.isNotEmpty() }?.let { BipeSyncedToken(null, it) }
        if (!key.startsWith(KEY_PREFIX)) return null
        val userId = value.substringBefore(SEPARATOR, missingDelimiterValue = "")
        val token = value.substringAfter(SEPARATOR, missingDelimiterValue = "")
        if (userId.isEmpty() || token.isEmpty() || key(userId) != key) return null
        return BipeSyncedToken(userId, token)
    }

    /** Names a token without keeping it: the vault remembers a rejected token by this value. */
    fun fingerprint(token: String): String = sha256(token)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
