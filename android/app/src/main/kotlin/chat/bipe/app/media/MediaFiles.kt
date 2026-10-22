// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import chat.bipe.app.common.MediaLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaFileHandle
import java.io.File
import java.nio.ByteBuffer

/** A decoded image; animated is set for an animated sticker on Android 9 and later. */
class LoadedImage(val bitmap: ImageBitmap, val animated: Drawable? = null)

/**
 * Media of chat messages through the SDK media cache. Encrypted media: the event's MediaSource
 * holds the key and the SDK decrypts on the phone (spec timeline-media notes). Decrypted files
 * stay in the app cache.
 */
private object MediaCache {
    val images = LruCache<String, LoadedImage>(60)
    val files = HashMap<String, Pair<MediaFileHandle, File>>()
    val filesLock = Mutex()
}

/** The row image: the event thumbnail when there is one, else the media itself, at most maxPixel on the long side. */
suspend fun MediaLoader.previewImage(media: MediaMessage, maxPixel: Int): LoadedImage? {
    val key = "${media.thumbnailSource?.url ?: media.source.url}#$maxPixel"
    MediaCache.images.get(key)?.let { return it }
    val bytes = runCatching {
        when {
            media.thumbnailSource != null -> client.getMediaContent(media.thumbnailSource.source)
            media.kind == MediaMessage.Kind.IMAGE || media.kind == MediaMessage.Kind.STICKER -> client.getMediaContent(media.source.source)
            else -> null
        }
    }.getOrNull() ?: return null
    val image = decodeImage(bytes, maxPixel, animated = media.isAnimated && media.thumbnailSource == null) ?: return null
    MediaCache.images.put(key, image)
    return image
}

/** The full image for media-viewer. */
suspend fun MediaLoader.fullImage(media: MediaMessage): LoadedImage? {
    val key = "${media.source.url}#full"
    MediaCache.images.get(key)?.let { return it }
    val bytes = runCatching { client.getMediaContent(media.source.source) }.getOrNull() ?: return null
    val image = decodeImage(bytes, 4096, animated = media.isAnimated) ?: return null
    MediaCache.images.put(key, image)
    return image
}

/**
 * Downloads the media to a file in the cache (for video, voice, files, share and save).
 * The SDK deletes the file when its handle goes away, so the handles stay while the app runs.
 */
suspend fun MediaLoader.file(context: Context, ref: MediaRef, filename: String, mimeType: String?): File = MediaCache.filesLock.withLock {
    MediaCache.files[ref.url]?.second?.takeIf { it.exists() }?.let { return@withLock it }
    // The app cache, which the FileProvider of the manifest shares (res/xml/media_paths.xml).
    val dir = File(context.cacheDir, "media").apply { mkdirs() }
    val handle = client.getMediaFile(ref.source, filename, mimeType ?: "application/octet-stream", true, dir.path)
    val file = File(handle.path())
    MediaCache.files[ref.url] = handle to file
    file
}

private suspend fun decodeImage(bytes: ByteArray, maxPixel: Int, animated: Boolean): LoadedImage? = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPixel || bounds.outHeight / (sample * 2) >= maxPixel) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return@withContext null
    val drawable = if (animated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }.getOrNull()
            ?.takeIf { it is AnimatedImageDrawable }
    } else {
        null
    }
    LoadedImage(bitmap.asImageBitmap(), drawable)
}
