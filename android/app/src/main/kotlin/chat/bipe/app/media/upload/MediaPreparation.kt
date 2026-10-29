// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import chat.bipe.app.media.Blurhash
import chat.bipe.shared.generated.BipeSettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.VideoInfo
import java.io.File
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.resume

/**
 * The setting bipe_chats_media_quality (state quality of spec upload-preview). Stored in the
 * preferences file bipe_settings under the settings row id chats.mediaQuality.
 */
enum class MediaQuality(val value: String, val title: Int) {
    STANDARD("standard", chat.bipe.shared.R.string.bipe_chats_media_quality_standard),
    HIGH("high", chat.bipe.shared.R.string.bipe_chats_media_quality_high),
    ;

    companion object {
        fun current(context: Context): MediaQuality {
            val stored = context.getSharedPreferences("bipe_settings", Context.MODE_PRIVATE)
                .getString(BipeSettingsRow.CHATS_MEDIA_QUALITY.id, null)
            return entries.firstOrNull { it.value == stored } ?: STANDARD
        }
    }
}

/**
 * A picked file made ready for Timeline.sendImage, sendVideo or sendFile (spec upload-preview notes):
 * photos at most 2048 px and videos at most 1280 px in standard quality, a thumbnail and a blurhash
 * for every image and video, and no location or camera data in photos.
 */
class PreparedMedia(
    val file: File,
    val info: Info,
    val thumbnail: File?,
) {
    sealed interface Info {
        data class Image(val info: ImageInfo) : Info

        data class Video(val info: VideoInfo) : Info

        data class File(val info: FileInfo) : Info
    }
}

object MediaPreparation {
    private const val MAX_PHOTO_SIDE = 2048
    private const val MAX_VIDEO_SIDE = 1280
    private const val THUMBNAIL_SIDE = 800

    /** A folder in the app cache for picked and prepared files. Upload sources must stay until the send queue has them. */
    fun workFolder(context: Context): File =
        File(context.cacheDir, "uploads/${UUID.randomUUID()}").apply { mkdirs() }

    fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    suspend fun prepare(context: Context, source: File, kind: PickedMedia.Kind, quality: MediaQuality): PreparedMedia =
        withContext(Dispatchers.IO) {
            val folder = workFolder(context)
            when (kind) {
                PickedMedia.Kind.IMAGE -> prepareImage(source, quality, folder)
                PickedMedia.Kind.VIDEO -> prepareVideo(context, source, quality, folder)
                PickedMedia.Kind.FILE -> PreparedMedia(
                    source,
                    PreparedMedia.Info.File(FileInfo(mimetype = mimeType(source), size = source.length().toULong(), thumbnailInfo = null, thumbnailSource = null)),
                    null,
                )
            }
        }

    // Images

    private fun prepareImage(source: File, quality: MediaQuality, folder: File): PreparedMedia {
        val name = source.nameWithoutExtension
        val type = mimeType(source)
        val output: File
        if (quality == MediaQuality.HIGH || type == "image/gif") {
            // As it is, without location and camera data where the format has EXIF.
            output = File(folder, source.name)
            source.copyTo(output, overwrite = true)
            if (type == "image/jpeg" || type == "image/png" || type == "image/webp") stripMetadata(output)
        } else {
            // Standard: at most 2048 px, JPEG. Encoding the pixels drops every property.
            val bitmap = decodeOriented(source, MAX_PHOTO_SIDE) ?: error("not an image")
            output = File(folder, "$name.jpg")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        val rotated = orientation(output) in listOf(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270)
        val width = if (rotated) bounds.outHeight else bounds.outWidth
        val height = if (rotated) bounds.outWidth else bounds.outHeight
        val thumbnail = decodeOriented(output, THUMBNAIL_SIDE)?.let { thumbnail(it, folder, name) }
        val info = ImageInfo(
            height = height.toULong(),
            width = width.toULong(),
            mimetype = mimeType(output),
            size = output.length().toULong(),
            thumbnailInfo = thumbnail?.info,
            thumbnailSource = null,
            blurhash = thumbnail?.blurhash,
            isAnimated = type == "image/gif",
        )
        return PreparedMedia(output, PreparedMedia.Info.Image(info), thumbnail?.file)
    }

    private fun orientation(file: File): Int =
        runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decodeOriented(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation(file)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            }
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private val metadataTags = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_IMAGE_UNIQUE_ID,
    )

    private fun stripMetadata(file: File) {
        runCatching {
            val exif = ExifInterface(file.path)
            metadataTags.forEach { exif.setAttribute(it, null) }
            exif.saveAttributes()
        }
    }

    private class Thumbnail(val file: File, val info: ThumbnailInfo, val blurhash: String)

    private fun thumbnail(bitmap: Bitmap, folder: File, name: String): Thumbnail {
        val file = File(folder, "$name-thumbnail.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        val info = ThumbnailInfo(
            height = bitmap.height.toULong(),
            width = bitmap.width.toULong(),
            mimetype = "image/jpeg",
            size = file.length().toULong(),
        )
        return Thumbnail(file, info, Blurhash.encode(bitmap))
    }

    // Videos

    private suspend fun prepareVideo(context: Context, source: File, quality: MediaQuality, folder: File): PreparedMedia {
        val name = source.nameWithoutExtension
        var output = source
        if (quality == MediaQuality.STANDARD) {
            val target = File(folder, "$name.mp4")
            if (transcode(context, source, target)) output = target
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val sideways = rotation == 90 || rotation == 270
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIDE, THUMBNAIL_SIDE)
            } else {
                retriever.getFrameAtTime(0)
            }
            val thumbnail = frame?.let { thumbnail(it, folder, name) }
            val info = VideoInfo(
                duration = durationMs?.let { Duration.ofMillis(it) },
                height = (if (sideways) rawWidth else rawHeight).toULong(),
                width = (if (sideways) rawHeight else rawWidth).toULong(),
                mimetype = mimeType(output),
                size = output.length().toULong(),
                thumbnailInfo = thumbnail?.info,
                thumbnailSource = null,
                blurhash = thumbnail?.blurhash,
            )
            return PreparedMedia(output, PreparedMedia.Info.Video(info), thumbnail?.file)
        } finally {
            retriever.release()
        }
    }

    /** H.264 at most 1280 px on the long side, without the source metadata. false: send the source as it is. */
    private suspend fun transcode(context: Context, source: File, target: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val resize = if (longSide(source) > MAX_VIDEO_SIDE) {
                listOf(Presentation.createForWidthAndHeight(MAX_VIDEO_SIDE, MAX_VIDEO_SIDE, Presentation.LAYOUT_SCALE_TO_FIT))
            } else {
                emptyList()
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source.toURI().toString()))
                .setEffects(Effects(emptyList(), resize))
                .build()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
                .build()
            transformer.start(item, target.path)
            continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
        }
    }

    private fun longSide(video: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(video.path)
            maxOf(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            Int.MAX_VALUE
        } finally {
            retriever.release()
        }
    }
}
