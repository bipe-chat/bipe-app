// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A sign-up whose credentials are not stored yet (ADR 0094). The key makes a retry return the same account. The PIN is
 * the one the first try sent.
 */
@Serializable
data class BipePendingSignup(
    @SerialName("key") val key: String,
    @SerialName("pin") val pin: String? = null,
) {
    override fun toString(): String = "BipePendingSignup"
}

/** The device-only secrets of one account. */
@Serializable
data class BipeVaultAccount(
    /** The hidden MAS password (ADR 0059: this device only). */
    @SerialName("password") val password: String? = null,
    /**
     * The recovery token. Android keeps it here, on this phone only: the synced store (Block Store) is not in bipe-app
     * yet, its licence needs an ADR (bipe-chat/bipe#554).
     */
    @SerialName("token") val token: String? = null,
) {
    override fun toString(): String = "BipeVaultAccount"
}

/** A recovery by e-mail that waits for its delay on this phone (ADR 0057). The claim secret proves this phone. */
@Serializable
data class BipePendingClaim(
    @SerialName("recovery_id") val recoveryId: String,
    @SerialName("claim_secret") val claimSecret: String,
    @SerialName("purpose") val purposeId: String,
    @SerialName("ready_at") val readyAtMillis: Long,
) {
    override fun toString(): String = "BipePendingClaim($recoveryId)"
}

@Serializable
data class BipeVaultData(
    /** Matrix ID to its secrets (ADR 0086). The last entry is the newest. */
    @SerialName("accounts") val accounts: Map<String, BipeVaultAccount> = emptyMap(),
    @SerialName("pending_signup") val pendingSignup: BipePendingSignup? = null,
    /** New account to its inviter's Matrix ID, until signup-done opens the chat (ADR 0034). */
    @SerialName("pending_inviters") val pendingInviters: Map<String, String> = emptyMap(),
    @SerialName("claim") val claim: BipePendingClaim? = null,
) {
    override fun toString(): String = "BipeVaultData"
}

/**
 * Device-only secrets of device-first accounts: one file in `noBackupFilesDir`, encrypted with an Android Keystore key
 * (AES-GCM). Android leaves that folder out of backups and device transfer, and the key cannot leave the phone.
 * Never logged.
 */
class BipeAccountVault(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): BipeVaultData = mutex.withLock { decode() ?: BipeVaultData() }

    /** Skips the write when the file cannot be read now: writing over it would lose the hidden password. */
    suspend fun update(transform: (BipeVaultData) -> BipeVaultData) = mutex.withLock {
        val current = decode() ?: return@withLock
        save(transform(current))
    }

    /** Keeps new credentials under their Matrix ID and makes the account the newest. */
    suspend fun store(userId: String, password: String, token: String) = update { data ->
        data.copy(accounts = data.accounts - userId + (userId to BipeVaultAccount(password, token)))
    }

    /** The pending sign-up, or a new one with a fresh key. The new one is stored before the first request goes out. */
    suspend fun pendingSignup(pin: String): BipePendingSignup {
        read().pendingSignup?.let { pending ->
            if (pending.pin != null) return pending
            return pending.copy(pin = pin).also { updated -> update { it.copy(pendingSignup = updated) } }
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pending = BipePendingSignup(Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), pin)
        update { it.copy(pendingSignup = pending) }
        return pending
    }

    /** `null` when the file exists but cannot be read now (a Keystore error that may pass). */
    private suspend fun decode(): BipeVaultData? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext BipeVaultData()
        try {
            val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
            json.decodeFromString(BipeVaultData.serializer(), cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).decodeToString())
        } catch (error: Exception) {
            if (error.isKeyLost()) {
                // A lost Keystore key (a device reset) makes the file unreadable for good.
                file.delete()
                BipeVaultData()
            } else {
                null
            }
        }
    }

    private suspend fun save(data: BipeVaultData) = withContext(Dispatchers.IO) {
        if (data == BipeVaultData()) {
            file.delete()
            return@withContext
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(BipeVaultData.serializer(), data).encodeToByteArray())
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Only these mean the file can never be read again. Other Keystore errors happen on some phones and pass. */
    private fun Throwable.isKeyLost(): Boolean = generateSequence(this) { it.cause }.any {
        it is AEADBadTagException || it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException ||
            it is SerializationException || it is IllegalArgumentException
    }

    private companion object {
        const val FILE_NAME = "bipe-account"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bipe_account"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
