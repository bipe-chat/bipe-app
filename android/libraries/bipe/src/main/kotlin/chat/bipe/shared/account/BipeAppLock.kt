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
 * device; a wrong app lock PIN never counts against the account, but it counts against the local try limit
 * ([BipeAppLockTries]). Never logged.
 *
 * A lock file that cannot be read (a Keystore or decrypt error, a broken file) keeps the lock on (bipe-chat/bipe#777):
 * only a file that is not there means off.
 */
class BipeAppLock(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(
        @SerialName("salt") val salt: String,
        @SerialName("hash") val hash: String,
        @SerialName("biometric_unlock") val biometricUnlock: Boolean = false,
        /** Wrong tries in a row; 0 in a file from before the try limit. */
        @SerialName("failures") val failures: Int = 0,
        @SerialName("last_failure") val lastFailure: Long = 0,
    ) {
        override fun toString(): String = "BipeAppLock.Stored"
    }

    /** The result of a PIN check. */
    sealed interface Check {
        data object Match : Check

        data object Wrong : Check

        /** Too many wrong tries: no check until [until] (wall-clock milliseconds). */
        data class Wait(val until: Long) : Check
    }

    private sealed interface Record {
        data object None : Record

        data class Present(val stored: Stored) : Record

        /** The Keystore or the file failed: the lock stays on. */
        data object Unreadable : Record
    }

    /** The lock is on while an app lock PIN is set, or while the lock file cannot be read. Off by default (ADR 0137). */
    val isOn: Boolean get() = read() != Record.None

    val biometricUnlock: Boolean get() = (read() as? Record.Present)?.stored?.biometricUnlock ?: false

    /** Sets the app lock PIN. Keeps the biometric flag when the lock was already on. Clears the wrong tries. Runs PBKDF2: call off the main thread. */
    fun setPin(pin: String): Boolean {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val stored = Stored(encode(salt), encode(hash(pin, salt)), biometricUnlock)
        return runCatching { write(stored) }.isSuccess
    }

    /**
     * Checks a PIN on the device, with the try limit. A wrong PIN is counted before the answer, so closing the app does
     * not undo it. An unreadable file never matches. Runs PBKDF2: call off the main thread.
     */
    @Synchronized
    fun checkPin(pin: String, now: Long = System.currentTimeMillis()): Check {
        var stored = (read() as? Record.Present)?.stored ?: return Check.Wrong
        stored = clockChecked(stored, now)
        BipeAppLockTries.waitEnd(stored.failures, stored.lastFailure, now)?.let { return Check.Wait(it) }
        if (MessageDigest.isEqual(hash(pin, decode(stored.salt)), decode(stored.hash))) {
            if (stored.failures > 0) runCatching { write(stored.copy(failures = 0, lastFailure = 0)) }
            return Check.Match
        }
        val failed = stored.copy(failures = stored.failures + 1, lastFailure = now)
        runCatching { write(failed) }
        return BipeAppLockTries.waitEnd(failed.failures, now, now)?.let { Check.Wait(it) } ?: Check.Wrong
    }

    /** The end of the current wait (wall-clock milliseconds), or null when a PIN can be typed. */
    @Synchronized
    fun waitUntil(now: Long = System.currentTimeMillis()): Long? {
        val stored = (read() as? Record.Present)?.stored ?: return null
        val checked = clockChecked(stored, now)
        return BipeAppLockTries.waitEnd(checked.failures, checked.lastFailure, now)
    }

    /** After a biometric unlock: the owner is back, so the wrong tries start again from zero. */
    @Synchronized
    fun clearFailures() {
        val stored = (read() as? Record.Present)?.stored ?: return
        if (stored.failures > 0) runCatching { write(stored.copy(failures = 0, lastFailure = 0)) }
    }

    fun setBiometricUnlock(isOn: Boolean) {
        val stored = (read() as? Record.Present)?.stored ?: return
        runCatching { write(stored.copy(biometricUnlock = isOn)) }
    }

    /** A clock set back before the last wrong try restarts the wait from now, so it cannot shorten the wait. */
    private fun clockChecked(stored: Stored, now: Long): Stored {
        if (stored.failures == 0 || stored.lastFailure <= now) return stored
        return stored.copy(lastFailure = now).also { runCatching { write(it) } }
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

    /** Only a missing file means no lock; any error keeps the lock on. */
    private fun read(): Record {
        if (!file.exists()) return Record.None
        return runCatching {
            val sealed = decode(file.readText())
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
            json.decodeFromString(Stored.serializer(), cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).decodeToString())
        }.fold({ Record.Present(it) }, { Record.Unreadable })
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
