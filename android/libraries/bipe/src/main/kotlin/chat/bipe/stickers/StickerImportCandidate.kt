// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from Bipe's libraries/bipe stickers/StickerImport.kt (Bipe code).

package chat.bipe.stickers

import chat.bipe.shared.generated.BipeRegistries
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
) {
    companion object {
        /** The platform probes dimensions. File type, limits, shortcode and WhatsApp tags are shared. */
        fun create(bytes: ByteArray, dimensions: (ByteArray) -> Pair<Int, Int>?): StickerImportCandidate? {
            if (bytes.size > BipeRegistries.STICKER_IMPORT_MAX_BYTES) return null
            val mimeType = mimeType(bytes) ?: return null
            if (StickerFormat.isAnimatedPng(bytes)) return null
            val (width, height) = dimensions(bytes) ?: return null
            val limit = BipeRegistries.STICKER_IMPORT_MAX_PIXELS
            if (width !in 1..limit || height !in 1..limit) return null
            val tags = if (mimeType == "image/webp") emojiTags(bytes) else emptyList()
            return StickerImportCandidate(bytes, mimeType, width, height, stickerShortcode(bytes), tags)
        }

        /**
         * WhatsApp puts {"emojis": [...]} as JSON in the EXIF chunk of a sticker WebP. Its emoji map to tags through
         * BipeRegistries.STICKER_EMOJI, in registry order. Empty for any other file.
         */
        internal fun emojiTags(webp: ByteArray): List<String> {
            var offset = 12
            while (offset + 8 <= webp.size) {
                val size = (webp[offset + 4].toInt() and 0xFF) or ((webp[offset + 5].toInt() and 0xFF) shl 8) or
                    ((webp[offset + 6].toInt() and 0xFF) shl 16) or ((webp[offset + 7].toInt() and 0xFF) shl 24)
                val start = offset + 8
                if (size < 0 || size > webp.size - start) return emptyList()
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

        private fun mimeType(bytes: ByteArray): String? {
            if (bytes.size < 12) return null
            val riff = bytes.sliceArray(0..3).contentEquals("RIFF".toByteArray())
            val webp = bytes.sliceArray(8..11).contentEquals("WEBP".toByteArray())
            if (riff && webp) return "image/webp"
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            if (bytes.sliceArray(0..7).contentEquals(png)) return "image/png"
            return null
        }
    }
}

internal fun stickerShortcode(bytes: ByteArray): String =
    "st_" + MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
