// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.invites

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * On-device memory for personal invites, per account (the same parts as BipeKit's BipeInviteStore):
 * - links: the server keeps only a hash of each code, so a link exists only on the phone that made it. A link is a
 *   bearer secret.
 * - notes (ADR 0136): the first message for the person who joins with an invite. The server never receives a note.
 *
 * Both live in one file in no-backup storage, sealed with an Android Keystore key (AES-GCM), so they never leave
 * this phone in a backup. Unreadable data stops migration without replacing the stored content.
 */
class BipeInviteStore(context: Context, userId: String) {
    @Serializable
    private data class Contents(
        @SerialName("links") val links: Map<String, String> = emptyMap(),
        @SerialName("notes") val notes: Map<String, String> = emptyMap(),
    )

    private val file = File(context.noBackupFilesDir, "bipe-invites-" + userId.hashCode().toUInt().toString(16))

    /** One consistent read for importing the earlier native-client format. Never reads fork storage. */
    suspend fun snapshotForMigration(): BipeInviteMemoryData = mutex.withLock {
        val saved = decode()
        BipeInviteMemoryData(saved.links, saved.notes)
    }

    /** Links of invites made on this phone, by invite ID. */
    suspend fun links(): Map<String, String> = read().links

    suspend fun saveLink(url: String, inviteId: String) = update { it.copy(links = it.links + (inviteId to url)) }

    /** Drops the links and notes of invites that are no longer pending (used, expired or revoked). */
    suspend fun keepOnly(pendingInviteIds: Set<String>) = update { contents ->
        contents.copy(links = contents.links.filterKeys { it in pendingInviteIds }, notes = contents.notes.filterKeys { it in pendingInviteIds })
    }

    /** Notes of invites made on this phone, by invite ID. */
    suspend fun notes(): Map<String, String> = read().notes

    suspend fun saveNote(note: String, inviteId: String) = update { it.copy(notes = it.notes + (inviteId to note)) }

    suspend fun removeNote(inviteId: String) = update { it.copy(notes = it.notes - inviteId) }

    /** Revoke: the link and the note go together. */
    suspend fun remove(inviteId: String) = update { Contents(it.links - inviteId, it.notes - inviteId) }

    private suspend fun read(): Contents = mutex.withLock { decode() }

    private suspend fun update(transform: (Contents) -> Contents) = mutex.withLock {
        val next = transform(decode())
        save(next)
    }

    private suspend fun decode(): Contents = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Contents()
        run {
            val sealed = Base64.decode(file.readText(), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_SIZE))
            json.decodeFromString(Contents.serializer(), cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE).decodeToString())
        }
    }

    private suspend fun save(contents: Contents) = withContext(Dispatchers.IO) {
        if (contents == Contents()) {
            file.delete()
            return@withContext
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(Contents.serializer(), contents).encodeToByteArray())
        val temp = File(file.parentFile, file.name + ".tmp")
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

    private companion object {
        val mutex = Mutex()
        val json = Json { ignoreUnknownKeys = true }
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bipe_invites"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
