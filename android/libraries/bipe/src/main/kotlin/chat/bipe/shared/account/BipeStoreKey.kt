// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The key of the SDK stores made since bipe-chat/bipe#913 (ADR 0073): 32 random bytes, one key for the whole install,
 * so a store keeps its key when it moves between folders (QR login). One file in `noBackupFilesDir`, encrypted with an
 * Android Keystore key. Never logged.
 *
 * Every error throws: a store whose key cannot be read now stays closed, and nothing writes over the file.
 */
class BipeStoreKey(context: Context) {
    private val file = File(context.noBackupFilesDir, FILE_NAME)

    /** The key, or `null` when this install has none. */
    @Synchronized
    fun read(): ByteArray? {
        if (!file.exists()) return null
        val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val wrapping = checkNotNull(keyStore.getKey(KEY_ALIAS, null) as? SecretKey) { "store key wrapping key missing" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).also { check(it.size == KEY_SIZE) { "store key size" } }
    }

    /** The key, made and saved when this install has none. */
    @Synchronized
    fun readOrCreate(): ByteArray {
        read()?.let { return it }
        val key = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, bipeKeystoreKey(KEY_ALIAS))
        val sealed = cipher.iv + cipher.doFinal(key)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(Base64.encodeToString(sealed, Base64.NO_WRAP))
        check(temp.renameTo(file)) { "store key not saved" }
        return key
    }

    private companion object {
        const val FILE_NAME = "bipe-store-key"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bipe_store_key"
        const val KEY_SIZE = 32
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}

/** The AES-GCM key under [alias] in the Android Keystore, made when missing. It cannot leave the phone. */
internal fun bipeKeystoreKey(alias: String): SecretKey {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build(),
    )
    return generator.generateKey()
}
