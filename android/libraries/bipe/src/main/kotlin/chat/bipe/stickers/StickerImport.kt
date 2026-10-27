// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from bipe-android libraries/bipe stickers/StickerImport.kt (Bipe code).

package chat.bipe.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** A sticker file ready to go into the user's pack. Mirrors StickerImport.swift in BipeKit. */
@Suppress("UseDataClass") // A ByteArray has no structural equality.
class StickerImportCandidate(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** Derived from the file's SHA-256, so the same file imported twice gets the same shortcode. */
    val shortcode: String,
    /** Tags from the emoji a WhatsApp WebP carries (spec sticker-import notes). Empty when it carries none. */
    val tags: List<String> = emptyList(),
)

/** Imports stickers from a WhatsApp chat export or from loose image files (ADR 0029, 0033). */
object StickerImporter {
    /** WhatsApp stickers are 512 × 512, static up to 100 KB, animated up to 500 KB. These limits leave room. */
    const val MAX_FILE_SIZE = 1_000_000
    const val MAX_SIDE = 1024

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

    /** Checks the format by its magic bytes and the size with BitmapFactory. Returns null when the file is not a usable sticker. */
    fun candidate(bytes: ByteArray): StickerImportCandidate? {
        if (bytes.size > MAX_FILE_SIZE) return null
        val mimeType = mimeType(bytes) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
        return StickerImportCandidate(bytes, mimeType, width, height, "st_$digest", if (mimeType == "image/webp") emojiTags(bytes) else emptyList())
    }

    /**
     * WhatsApp puts {"emojis": [...]} as JSON in the EXIF chunk of a sticker WebP. Its emoji map to tags through
     * BipeRegistries.STICKER_EMOJI, in registry order. Empty for any other file.
     */
    fun emojiTags(webp: ByteArray): List<String> {
        var offset = 12
        while (offset + 8 <= webp.size) {
            val size = (webp[offset + 4].toInt() and 0xFF) or ((webp[offset + 5].toInt() and 0xFF) shl 8) or
                ((webp[offset + 6].toInt() and 0xFF) shl 16) or ((webp[offset + 7].toInt() and 0xFF) shl 24)
            val start = offset + 8
            if (size < 0 || start + size > webp.size) return emptyList()
            if (String(webp, offset, 4, Charsets.US_ASCII) == "EXIF") {
                val chunk = webp.copyOfRange(start, start + size)
                val open = chunk.indexOf('{'.code.toByte())
                val close = chunk.lastIndexOf('}'.code.toByte())
                if (open < 0 || close <= open) return emptyList()
                val json = runCatching { Json.parseToJsonElement(String(chunk, open, close - open + 1, Charsets.UTF_8)) as? JsonObject }.getOrNull()
                val emojis = (json?.get("emojis") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?: return emptyList()
                val tags = emojis.flatMap { BipeRegistries.STICKER_EMOJI[StickerSearch.emojiKey(it)].orEmpty() }.toSet()
                return BipeRegistries.STICKER_TAGS.filter { it in tags }
            }
            offset = start + size + (size and 1)
        }
        return emptyList()
    }

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

    private fun mimeType(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val riff = bytes.sliceArray(0..3).contentEquals("RIFF".toByteArray())
        val webp = bytes.sliceArray(8..11).contentEquals("WEBP".toByteArray())
        if (riff && webp) return "image/webp"
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.sliceArray(0..7).contentEquals(png)) return "image/png"
        return null
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
