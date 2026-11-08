// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * Keeps the SDK session (tokens, device ID) of every account in app storage that is not backed up, one file per account
 * keyed by Matrix ID (ADR 0086), encrypted with an Android Keystore key that never leaves the phone. Never logged.
 */
class SessionStore(context: Context, folderName: String = DEFAULT_FOLDER) {
    private val folder = File(context.noBackupFilesDir, folderName)
    /** Only the store of the accounts reads the file of builds before bipe-chat/bipe#577. */
    private val migratesLegacy = folderName == DEFAULT_FOLDER
    /** The single file of builds before bipe-chat/bipe#577. [loadAll] moves it under its Matrix ID. */
    private val legacyFile = File(context.noBackupFilesDir, "session.bin")

    /** The saved session of every account on this phone, by Matrix ID. */
    fun loadAll(): Map<String, Session> {
        migrateLegacyFile()
        return folder.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
            .mapNotNull { read(it) }
            .associateBy { it.userId }
    }

    fun load(userId: String): Session? = read(file(userId))

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
        write(cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), file(session.userId))
    }

    fun delete(userId: String) {
        file(userId).delete()
    }

    /** Saves the session again when the SDK refreshes the tokens. */
    fun delegate(): ClientSessionDelegate = object : ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(userId: String): Session =
            load(userId) ?: throw ClientException.Generic("no saved session", null)

        override fun saveSessionInKeychain(session: Session) = save(session)
    }

    private fun file(userId: String): File {
        val name = Base64.encodeToString(userId.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return File(folder, name + SUFFIX)
    }

    private fun migrateLegacyFile() {
        if (!migratesLegacy || !legacyFile.exists()) return
        val session = read(legacyFile)
        if (session != null && !file(session.userId).exists()) {
            write(legacyFile.readBytes(), file(session.userId))
        }
        legacyFile.delete()
    }

    private fun read(file: File): Session? {
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

    private fun write(sealed: ByteArray, file: File) {
        folder.mkdirs()
        val temp = File(folder, file.name + ".tmp")
        temp.writeBytes(sealed)
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

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)

    companion object {
        const val DEFAULT_FOLDER = "sessions-saved"
        /**
         * The member identity sessions of Interno (bipe ADR 0284 point 5): apart from the accounts, so they never show
         * in the account switcher and never count against maxPersonalAccounts.
         */
        const val MEMBER_FOLDER = "member-sessions-saved"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "bipe_session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val SUFFIX = ".bin"
    }
}
