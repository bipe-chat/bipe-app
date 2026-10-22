// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The app lock store of spec app-lock (bipe-shared product/screens/app-lock.json), written from the spec.

package chat.bipe.shared.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The app lock PIN and the biometric unlock flag (spec app-lock, ADR 0137): one file in `noBackupFilesDir`, encrypted
 * with an Android Keystore key, never synced, never sent. The PIN is kept as a salted PBKDF2 hash and checked on the
 * device; a wrong app lock PIN never counts against the account. Never logged.
 */
class BipeAppLock(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(
        @SerialName("salt") val salt: String,
        @SerialName("hash") val hash: String,
        @SerialName("biometric_unlock") val biometricUnlock: Boolean = false,
    ) {
        override fun toString(): String = "BipeAppLock.Stored"
    }

    /** The lock is on while an app lock PIN is set. Off by default (ADR 0137). */
    val isOn: Boolean get() = read() != null

    val biometricUnlock: Boolean get() = read()?.biometricUnlock ?: false

    /** Sets the app lock PIN. Keeps the biometric flag when the lock was already on. Runs PBKDF2: call off the main thread. */
    fun setPin(pin: String): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val stored = Stored(encode(salt), encode(hash(pin, salt)), read()?.biometricUnlock ?: false)
        return runCatching { write(stored) }.isSuccess
    }

    /** Checks a PIN on the device. Runs PBKDF2: call off the main thread. */
    fun verifyPin(pin: String): Boolean {
        val stored = read() ?: return false
        return MessageDigest.isEqual(hash(pin, decode(stored.salt)), decode(stored.hash))
    }

    fun setBiometricUnlock(isOn: Boolean) {
        val stored = read() ?: return
        runCatching { write(stored.copy(biometricUnlock = isOn)) }
    }

    /** Turns the lock off: removes the app lock PIN and biometric unlock. */
    fun remove() {
        file.delete()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun read(): Stored? {
        if (!file.exists()) return null
        return runCatching {
            val sealed = decode(file.readText())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
            json.decodeFromString(Stored.serializer(), cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).decodeToString())
        }.getOrNull()
    }

    private fun write(stored: Stored) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(Stored.serializer(), stored).encodeToByteArray())
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(encode(sealed))
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

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val FILE_NAME = "bipe-app-lock"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bipe_app_lock"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
        const val ITERATIONS = 100_000
    }
}
