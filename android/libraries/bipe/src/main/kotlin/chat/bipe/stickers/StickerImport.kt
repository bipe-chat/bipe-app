// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from Bipe's libraries/bipe stickers/StickerImport.kt (Bipe code).

package chat.bipe.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import chat.bipe.shared.generated.BipeRegistries
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Imports stickers from a WhatsApp chat export or from loose image files (ADR 0029, 0033). */
object StickerImporter {
    /** WhatsApp stickers are 512 × 512, static up to 100 KB, animated up to 500 KB. These limits leave room. */
    const val MAX_FILE_SIZE = BipeRegistries.STICKER_IMPORT_MAX_BYTES
    const val MAX_SIDE = BipeRegistries.STICKER_IMPORT_MAX_PIXELS

    /** WhatsApp's sticker size. */
    const val STICKER_SIDE = 512

    /**
     * Sticker files from one picked file. A ZIP gives its `.webp` entries; a WebP or PNG file gives itself.
     * Files that fail a check are skipped.
     */
    fun candidates(fileName: String?, open: () -> InputStream?): List<StickerImportCandidate> {
        val isZip = fileName?.lowercase()?.endsWith(".zip") == true
        return open()?.use { stream ->
            if (isZip) {
                zipEntries(stream).mapNotNull(::candidate)
            } else {
                listOfNotNull(stream.readAtMost(MAX_FILE_SIZE)?.let(::candidate))
            }
        }.orEmpty()
    }

    /**
     * Checks the format by its magic bytes and the size with BitmapFactory. Returns null when the file is not a usable sticker.
     * Animated WebP passes; an animated PNG does not (ADR 0205: animated WebP is the one animated format).
     */
    fun candidate(bytes: ByteArray): StickerImportCandidate? = StickerImportCandidate.create(bytes) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(it, 0, it.size, options)
        options.outWidth to options.outHeight
    }

    fun emojiTags(webp: ByteArray): List<String> = StickerImportCandidate.emojiTags(webp)

    /** st_ plus the first 8 bytes of the SHA-256 in hex. A saved received sticker hashes its mxc:// URL. */
    fun shortcode(bytes: ByteArray): String = stickerShortcode(bytes)

    /**
     * "Make from a photo": crops a centred square, scales it to 512 × 512 and encodes WebP.
     * No subject cut-out on Android: that needs ML Kit, a Google dependency (not decided).
     */
    fun fromPhoto(photo: Bitmap): StickerImportCandidate? {
        val side = minOf(photo.width, photo.height)
        if (side <= 0) return null
        val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, STICKER_SIDE, STICKER_SIDE, true)
        val output = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, 90, output)
        return candidate(output.toByteArray())
    }

    private fun zipEntries(stream: InputStream): List<ByteArray> {
        val result = mutableListOf<ByteArray>()
        ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".webp")) {
                    zip.readAtMost(MAX_FILE_SIZE)?.let { result += it }
                }
            }
        }
        return result
    }

    /** Reads the stream, or the current ZIP entry, up to [limit] bytes. Returns null when it is larger. */
    private fun InputStream.readAtMost(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) return null
        }
        return output.toByteArray()
    }
}
