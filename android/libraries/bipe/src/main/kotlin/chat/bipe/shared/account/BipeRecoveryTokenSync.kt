// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A recovery token in the synced store, with the account it opens (ADR 0059). [savedAt] is epoch milliseconds. */
data class BipeSyncedToken(val userId: String, val token: String, val savedAt: Long) {
    override fun toString(): String = "BipeSyncedToken($userId)"
}

/**
 * The synced store of recovery tokens: Block Store in the Android Play build (ADR 0150). The build without Google Play
 * services uses [None]. Every call throws when the store fails; [BipeRecoveryTokens] keeps the work and tries again.
 */
interface BipeRecoveryTokenSync {
    /** False when this build has no synced store. */
    val isAvailable: Boolean

    /** Writes the token of [BipeSyncedToken.userId], in place of an older one. */
    suspend fun store(entry: BipeSyncedToken)

    /** Every Bipe recovery token in the store. Entries that are not Bipe's, or are damaged, are left out. */
    suspend fun all(): List<BipeSyncedToken>

    /** Removes the token of [userId]. Nothing happens when there is none. */
    suspend fun delete(userId: String)

    /** No synced store: the token stays in [BipeAccountVault], on this phone only. */
    object None : BipeRecoveryTokenSync {
        override val isAvailable = false
        override suspend fun store(entry: BipeSyncedToken) = Unit
        override suspend fun all(): List<BipeSyncedToken> = emptyList()
        override suspend fun delete(userId: String) = Unit
    }
}

/**
 * The format of one synced entry. One key per account, made from a hash of the Matrix ID, so the store's key list does
 * not name the account. The value is a small JSON object with the Matrix ID, the token and the time it was saved.
 */
object BipeSyncedTokenCodec {
    /** Block Store keeps at most 1024 bytes per entry. */
    const val MAX_BYTES = 1024
    private const val KEY_PREFIX = "bipe_recovery_"
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Wire(
        @SerialName("v") val version: Int,
        @SerialName("user_id") val userId: String,
        @SerialName("token") val token: String,
        @SerialName("saved_at") val savedAt: Long,
    )

    fun key(userId: String): String = KEY_PREFIX + sha256(userId).take(32)

    /** Throws [IllegalArgumentException] when the entry does not fit in [MAX_BYTES]. */
    fun encode(entry: BipeSyncedToken): ByteArray {
        val bytes = json.encodeToString(Wire.serializer(), Wire(VERSION, entry.userId, entry.token, entry.savedAt)).encodeToByteArray()
        require(bytes.size <= MAX_BYTES) { "recovery token entry too large" }
        return bytes
    }

    /**
     * `null` for a key that is not Bipe's, a damaged or unknown value, or a value stored under another account's key:
     * an entry only ever opens the account its key names.
     */
    fun decode(key: String, bytes: ByteArray): BipeSyncedToken? {
        if (!key.startsWith(KEY_PREFIX)) return null
        val wire = runCatching { json.decodeFromString(Wire.serializer(), bytes.decodeToString()) }.getOrNull() ?: return null
        if (wire.version != VERSION || wire.userId.isBlank() || wire.token.isBlank()) return null
        if (key(wire.userId) != key) return null
        return BipeSyncedToken(wire.userId, wire.token, wire.savedAt)
    }

    /** Names a token without keeping it: the vault remembers a rejected token by this value. */
    fun fingerprint(token: String): String = sha256(token)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
