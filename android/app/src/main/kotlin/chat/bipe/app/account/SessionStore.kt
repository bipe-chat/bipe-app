// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Keeps the SDK session (tokens, device ID) in app storage that is not backed up, encrypted with an
 * Android Keystore key that never leaves the phone. Never logged.
 */
class SessionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "session.bin")

    fun load(): Session? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            val json = JSONObject(String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8))
            Session(
                accessToken = json.getString("accessToken"),
                refreshToken = json.optStringOrNull("refreshToken"),
                userId = json.getString("userId"),
                deviceId = json.getString("deviceId"),
                homeserverUrl = json.getString("homeserverUrl"),
                oauthData = json.optStringOrNull("oauthData"),
                slidingSyncVersion = if (json.optBoolean("nativeSlidingSync")) SlidingSyncVersion.NATIVE else SlidingSyncVersion.NONE,
            )
        }.getOrNull()
    }

    fun save(session: Session) {
        val json = JSONObject()
            .put("accessToken", session.accessToken)
            .put("refreshToken", session.refreshToken ?: JSONObject.NULL)
            .put("userId", session.userId)
            .put("deviceId", session.deviceId)
            .put("homeserverUrl", session.homeserverUrl)
            .put("oauthData", session.oauthData ?: JSONObject.NULL)
            .put("nativeSlidingSync", session.slidingSyncVersion == SlidingSyncVersion.NATIVE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val temp = File(file.parentFile, "session.bin.tmp")
        temp.writeBytes(sealed)
        temp.renameTo(file)
    }

    fun delete() {
        file.delete()
    }

    /** Saves the session again when the SDK refreshes the tokens. */
    fun delegate(): ClientSessionDelegate = object : ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(userId: String): Session =
            load()?.takeIf { it.userId == userId } ?: throw ClientException.Generic("no saved session", null)

        override fun saveSessionInKeychain(session: Session) = save(session)
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

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bipe_session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
