// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.desktop.account.AccountLifetime
import chat.bipe.desktop.account.AccountResource
import chat.bipe.desktop.account.AccountTaskGroup
import chat.bipe.desktop.account.NativeResource
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource

/** Authenticated SDK thumbnails. No global cache, bearer URL, independent HTTP client or plaintext disk cache. */
class AccountMedia private constructor(
    private val group: AccountTaskGroup,
    private val fetch: suspend (String, Int) -> ByteArray,
) : AccountResource {
    private data class Key(val url: String, val pixels: Int)
    private val monitor = Any()
    private val cache = LinkedHashMap<Key, ByteArray>(16, 0.75f, true)
    private val requests = mutableMapOf<Key, Deferred<ByteArray?>>()
    private val downloads = Semaphore(4)
    private var cacheBytes = 0

    suspend fun thumbnail(url: String, pixels: Int): ByteArray? {
        group.requireCurrent()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "mxc" || uri.rawAuthority.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawPath.isNullOrBlank() || uri.rawPath == "/" || uri.rawQuery != null || uri.rawFragment != null) return null
        val key = Key(url, pixels.coerceIn(1, 256))
        val task = synchronized(monitor) {
            cache[key]?.let { return it.copyOf() }
            requests[key] ?: group.async { load(key) }.also { created ->
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

    private suspend fun load(key: Key): ByteArray? = downloads.withPermit {
        group.requireCurrent()
        val bytes = try { fetch(key.url, key.pixels) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@withPermit null }
        // Bound this 4GB target's encoded in-memory cache; oversized or invalid media retains initials.
        if (bytes.isEmpty() || bytes.size > 2 * 1024 * 1024) return@withPermit null
        group.requireCurrent()
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
        suspend fun create(owner: AccountLifetime, client: Client): AccountMedia = create(owner) { url, pixels ->
            val source = MediaSource.fromUrl(url)
            try { client.getMediaThumbnail(source, pixels.toULong(), pixels.toULong()) }
            finally { source.destroy() }
        }

        internal suspend fun create(owner: AccountLifetime, fetch: suspend (String, Int) -> ByteArray): AccountMedia {
            val group = AccountTaskGroup.create(owner)
            val media = AccountMedia(group, fetch)
            group.own(NativeResource { synchronized(media.monitor) {
                media.cache.values.forEach { it.fill(0) }
                media.cache.clear(); media.requests.clear(); media.cacheBytes = 0
            } })
            return media
        }
    }
}
