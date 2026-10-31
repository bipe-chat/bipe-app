// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import java.io.File

/** Spec data-use, storage (bipe-chat/bipe#826): the space Bipe uses on this phone. Sizes in bytes. */
data class StorageUse(
    /** mediaCacheBytes: the SDK media store and the app's media files that are not voice. */
    val mediaCacheBytes: Long,
    /** voiceBytes: the voice files in the app's media folders. */
    val voiceBytes: Long,
    /** storeBytes: messages, chat state and keys (state, event cache and crypto stores). */
    val storeBytes: Long,
) {
    val total: Long get() = mediaCacheBytes + voiceBytes + storeBytes
}

/**
 * Spec data-use notes: measures the SDK stores and the app's media folders, and deletes the folder that
 * clearMediaCache deletes.
 */
object StorageMeter {
    /** A file with one of these extensions counts as voice. */
    val VOICE_EXTENSIONS = setOf("ogg", "oga", "opus", "m4a", "aac", "mp3", "wav", "caf")

    private fun mediaFolder(context: Context) = File(context.cacheDir, "media")

    /** Every folder that counts (spec data-use notes). */
    private fun measuredFolders(context: Context) = listOf(mediaFolder(context), File(context.cacheDir, "uploads"))

    /** Action measureStorage, off the main thread. */
    suspend fun measure(context: Context, client: Client): StorageUse {
        val sizes = runCatching { client.getStoreSizes() }.getOrNull()
        val (media, voice) = withContext(Dispatchers.IO) { folderBytes(measuredFolders(context)) }
        val mediaStore = sizes?.mediaStore?.toLong() ?: 0L
        val stores = listOf(sizes?.stateStore, sizes?.eventCacheStore, sizes?.cryptoStore).sumOf { it?.toLong() ?: 0L }
        return StorageUse(mediaCacheBytes = mediaStore + media, voiceBytes = voice, storeBytes = stores)
    }

    /**
     * clearMediaCache: cacheDir/media, where the SDK writes downloaded files. Uploads stay: a send may still need
     * them.
     */
    suspend fun deleteAppMedia(context: Context) = withContext(Dispatchers.IO) {
        mediaFolder(context).deleteRecursively()
        Unit
    }

    private fun folderBytes(folders: List<File>): Pair<Long, Long> {
        var media = 0L
        var voice = 0L
        for (folder in folders) {
            folder.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.extension.lowercase() in VOICE_EXTENSIONS) voice += file.length() else media += file.length()
            }
        }
        return media to voice
    }
}
