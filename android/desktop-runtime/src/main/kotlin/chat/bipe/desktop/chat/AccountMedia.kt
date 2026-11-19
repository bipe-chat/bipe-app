// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.NativeResource
import chat.bipe.desktop.account.TaskResource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MediaPreviewConfig
import org.matrix.rustcomponents.sdk.MediaPreviewConfigListener

/** What AccountMedia asks the SDK; tests replace it. */
internal interface MediaBackend {
    /** A thumbnail of an unencrypted mxc URL (avatars). */
    suspend fun thumbnail(url: String, pixels: Int): ByteArray
    suspend fun originalAvatar(url: String): ByteArray = thumbnail(url, 256)
    /** The bytes of an event's media; the SDK downloads and decrypts with the key in the source JSON. */
    suspend fun content(sourceJson: String): ByteArray
    /** Downloads and decrypts an event's media and moves the file to [destination]. */
    suspend fun save(sourceJson: String, filename: String, mimeType: String, destination: Path)
}

/**
 * Authenticated SDK media. No global cache, bearer URL, independent HTTP client or plaintext disk cache:
 * decrypted bytes stay in this account's bounded memory cache, and a file reaches the disk only where the person saves it.
 */
sealed interface MediaPreference {
    data object Loading : MediaPreference
    data object Unavailable : MediaPreference
    data class Ready(val value: ShowMedia) : MediaPreference
    data class Saving(val before: ShowMedia, val requested: ShowMedia) : MediaPreference
    data class Failed(val before: ShowMedia, val requested: ShowMedia) : MediaPreference

    val current get() = when (this) {
        is Ready -> value
        is Saving -> before
        is Failed -> before
        else -> null
    }
}

class AccountMedia private constructor(
    private val group: AccountTaskGroup,
    private val backend: MediaBackend,
    private val settings: Client?,
) : AccountResource {
    private data class Key(val url: String, val pixels: Int)
    private val monitor = Any()
    private val cache = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private val requests = mutableMapOf<Key, Deferred<ByteArray?>>()
    private val downloads = Semaphore(4)
    private var cacheBytes = 0
    private val mutablePreference = MutableStateFlow<MediaPreference>(MediaPreference.Loading)
    val preference = mutablePreference.asStateFlow()

    internal fun listenPreferences() {
        val client = settings ?: return
        group.launch {
            try {
                group.own(TaskResource(client.subscribeToMediaPreviewConfig(object : MediaPreviewConfigListener {
                    override fun onChange(mediaPreviewConfig: MediaPreviewConfig?) = group.publish {
                        val incoming = ShowMedia.of(mediaPreviewConfig?.mediaPreviews)
                        val current = mutablePreference.value
                        if (current !is MediaPreference.Saving || incoming == current.requested)
                            mutablePreference.value = MediaPreference.Ready(incoming)
                    }
                })))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The explicit initial read still supplies the local setting. */ }
            refreshPreference()
        }
    }

    fun refreshPreference() = group.launch {
        val client = settings ?: return@launch
        val state = try { MediaPreference.Ready(ShowMedia.of(client.getMediaPreviewDisplayPolicy())) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MediaPreference.Unavailable }
        group.publish { if (mutablePreference.value.current == null) mutablePreference.value = state }
    }

    fun setPreference(requested: ShowMedia) {
        val client = settings ?: return
        var accepted: MediaPreference.Saving? = null
        group.publish {
            val before = mutablePreference.value
            val current = before.current
            if (current != null && current != requested && before !is MediaPreference.Saving) {
                accepted = MediaPreference.Saving(current, requested)
                mutablePreference.value = checkNotNull(accepted)
            }
        }
        val attempt = accepted ?: return
        group.launch {
            val result = try {
                client.setMediaPreviewDisplayPolicy(requested.sdk())
                MediaPreference.Ready(requested)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { MediaPreference.Failed(attempt.before, requested) }
            group.publish { if (mutablePreference.value === attempt) mutablePreference.value = result }
        }
    }

    suspend fun thumbnail(url: String, pixels: Int): ByteArray? {
        group.requireCurrent()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "mxc" || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawPath.isNullOrBlank() || uri.rawPath == "/" || uri.rawQuery != null || uri.rawFragment != null) return null
        val size = pixels.coerceIn(1, 256)
        return cached(Key(url, size), CACHE_ITEM_BYTES) { backend.thumbnail(url, size) }
    }

    /**
     * The picture of a timeline row (spec timeline-media appear): an event thumbnail, an image or a sticker.
     * Up to 16 MB is downloaded; only pictures up to 2 MB stay in the cache. Null when it cannot load.
     */
    suspend fun eventPicture(sourceJson: String): ByteArray? {
        group.requireCurrent()
        if (sourceJson.isBlank() || sourceJson.length > 64 * 1024) return null
        return cached(Key(EVENT_PREFIX + sourceJson, 0), EVENT_PICTURE_BYTES) { backend.content(sourceJson) }
    }

    suspend fun originalAvatar(url: String): ByteArray? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "mxc" || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawPath.isNullOrBlank() || uri.rawPath == "/" || uri.rawQuery != null || uri.rawFragment != null) return null
        return cached(Key(url, 0), EVENT_PICTURE_BYTES) { backend.originalAvatar(url) }
    }

    /** Saves an event's media where the person chose (spec timeline-media open, for a computer). Throws when it fails. */
    suspend fun save(media: MessageMedia, destination: Path) {
        group.requireCurrent()
        require(destination.isAbsolute && destination.parent != null && Files.isDirectory(destination.parent))
        group.async { backend.save(media.sourceJson, media.filename, media.mimeType ?: "application/octet-stream", destination) }.await()
    }

    private suspend fun cached(key: Key, limit: Int, fetch: suspend () -> ByteArray): ByteArray? {
        val task = synchronized(monitor) {
            cache[key]?.let { return it.copyOf() }
            requests[key] ?: group.async { load(key, limit, fetch) }.also { created ->
                requests[key] = created
                // A scrolled-away caller can stop awaiting; its completed request must still leave this map.
                created.invokeOnCompletion { synchronized(monitor) { requests.remove(key, created) } }
            }
        }
        try {
            val bytes = task.await()
            group.requireCurrent()
            return bytes?.copyOf()
        } finally {
            if (task.isCompleted) synchronized(monitor) { requests.remove(key, task) }
        }
    }

    private suspend fun load(key: Key, limit: Int, fetch: suspend () -> ByteArray): ByteArray? = downloads.withPermit {
        group.requireCurrent()
        val bytes = try { fetch() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withPermit null }
        // Bound this 4GB target's encoded in-memory cache; oversized or invalid media retains initials or the failed state.
        if (bytes.isEmpty() || bytes.size > limit) { bytes.fill(0); return@withPermit null }
        group.requireCurrent()
        if (bytes.size > CACHE_ITEM_BYTES) return@withPermit bytes
        group.publish { synchronized(monitor) {
            cache[key] = bytes.copyOf()
            cacheBytes += bytes.size
            while (cacheBytes > 8 * 1024 * 1024 || cache.size > 100) {
                val entry = cache.entries.iterator().next()
                cacheBytes -= entry.value.size
                entry.value.fill(0)
                cache.remove(entry.key)
            }
        } }
        bytes
    }

    override suspend fun close() = group.close()

    companion object {
        private const val CACHE_ITEM_BYTES = 2 * 1024 * 1024
        private const val EVENT_PICTURE_BYTES = 16 * 1024 * 1024
        private const val EVENT_PREFIX = "event:"

        suspend fun create(owner: AccountLifetime, client: Client): AccountMedia = create(owner, object : MediaBackend {
            override suspend fun thumbnail(url: String, pixels: Int): ByteArray {
                val source = MediaSource.fromUrl(url)
                try { return client.getMediaThumbnail(source, pixels.toULong(), pixels.toULong()) }
                finally { source.destroy() }
            }

            override suspend fun originalAvatar(url: String): ByteArray {
                val source = MediaSource.fromUrl(url)
                try { return client.getMediaContent(source) } finally { source.destroy() }
            }

            override suspend fun content(sourceJson: String): ByteArray {
                val source = MediaSource.fromJson(sourceJson)
                try { return client.getMediaContent(source) }
                finally { source.destroy() }
            }

            override suspend fun save(sourceJson: String, filename: String, mimeType: String, destination: Path) {
                val source = MediaSource.fromJson(sourceJson)
                try {
                    // The SDK writes the decrypted file next to the destination, then moves it there: no copy elsewhere.
                    val handle = client.getMediaFile(source, filename, mimeType, false, destination.parent.toString())
                    try { check(handle.persist(destination.toString())) } finally { handle.destroy() }
                } finally { source.destroy() }
            }
        }, client)

        internal suspend fun create(owner: AccountLifetime, fetch: suspend (String, Int) -> ByteArray): AccountMedia =
            create(owner, object : MediaBackend {
                override suspend fun thumbnail(url: String, pixels: Int) = fetch(url, pixels)
                override suspend fun content(sourceJson: String): ByteArray = error("No event media in this test")
                override suspend fun save(sourceJson: String, filename: String, mimeType: String, destination: Path) = error("No event media in this test")
            })

        internal suspend fun create(owner: AccountLifetime, backend: MediaBackend, settings: Client? = null): AccountMedia {
            val group = AccountTaskGroup.create(owner)
            val media = AccountMedia(group, backend, settings)
            group.own(NativeResource { synchronized(media.monitor) {
                media.cache.values.forEach { it.fill(0) }
                media.cache.clear(); media.requests.clear(); media.cacheBytes = 0
            } })
            return media
        }
    }
}
