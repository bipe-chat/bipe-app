// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.invites

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Device-only links and first messages. Account IDs partition the data; the file and key belong to Bipe Next. */
@Serializable
data class BipeInviteMemoryData(
    val links: Map<String, String> = emptyMap(),
    val notes: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "BipeInviteMemoryData"
}

@Serializable
private data class InviteAccounts(val accounts: Map<String, BipeInviteMemoryData> = emptyMap())

/** Bipe's existing invite-store protocol with native Android Keystore encryption, without fork adapters. */
class BipeInviteMemory(context: Context, private val userId: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bipe-native-invites"))
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): BipeInviteMemoryData = mutex.withLock {
        withContext(Dispatchers.IO) { load().accounts[userId] ?: BipeInviteMemoryData() }
    }

    suspend fun update(transform: (BipeInviteMemoryData) -> BipeInviteMemoryData) = mutex.withLock {
        withContext(Dispatchers.IO) {
            // A temporarily unreadable file must never become an empty replacement.
            val current = load()
            val updated = transform(current.accounts[userId] ?: BipeInviteMemoryData())
            save(current.copy(accounts = current.accounts + (userId to updated)))
        }
    }

    suspend fun keepPending(ids: Set<String>) = update { it.copy(links = it.links.filterKeys(ids::contains)) }

    suspend fun saveCreated(id: String, link: String, note: String) = update {
        val trimmed = note.trim()
        it.copy(links = it.links + (id to link), notes = if (trimmed.isEmpty()) it.notes else it.notes + (id to trimmed))
    }

    suspend fun remove(id: String) = update { it.copy(links = it.links - id, notes = it.notes - id) }

    /** One sender claims a note; a failed enqueue returns it with [restoreNote]. */
    suspend fun takeNote(id: String): String? {
        var taken: String? = null
        update { current -> taken = current.notes[id]; current.copy(notes = current.notes - id) }
        return taken
    }

    suspend fun restoreNote(id: String, note: String) = update { it.copy(notes = it.notes + (id to note)) }

    private fun load(): InviteAccounts {
        if (!file.baseFile.exists()) return InviteAccounts()
        val sealed = file.openRead().use { it.readBytes() }
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return json.decodeFromString(InviteAccounts.serializer(), cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES).decodeToString())
    }

    private fun save(data: InviteAccounts) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.encodeToString(InviteAccounts.serializer(), data).encodeToByteArray())
        val output = file.startWrite()
        try { output.write(sealed); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private companion object {
        val mutex = Mutex()
        const val KEY_ALIAS = "bipe_next_invites"
        const val IV_BYTES = 12
    }
}
