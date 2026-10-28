// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.stickers

/**
 * Reads the container of a sticker file without decoding it (ADR 0205 rule 2: animated WebP is the one
 * animated format). Mirrors StickerFormat.swift in BipeKit.
 */
object StickerFormat {
    /** true for a WebP with more than one frame (ANMF chunks of the RIFF container). */
    fun isAnimatedWebP(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || !bytes.hasAscii(0, "RIFF") || !bytes.hasAscii(8, "WEBP")) return false
        var frames = 0
        var offset = 12L
        while (offset + 8 <= bytes.size) {
            val at = offset.toInt()
            val size = bytes.u8(at + 4).toLong() or (bytes.u8(at + 5).toLong() shl 8) or
                (bytes.u8(at + 6).toLong() shl 16) or (bytes.u8(at + 7).toLong() shl 24)
            if (bytes.hasAscii(at, "ANMF")) {
                frames++
                if (frames > 1) return true
            }
            offset += 8 + size + (size and 1)
        }
        return false
    }

    /** true for an animated PNG: an acTL chunk before the first IDAT. The import rejects it (spec sticker-import). */
    fun isAnimatedPng(bytes: ByteArray): Boolean {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return false
        var offset = 8L
        while (offset + 8 <= bytes.size) {
            val at = offset.toInt()
            val size = (bytes.u8(at).toLong() shl 24) or (bytes.u8(at + 1).toLong() shl 16) or
                (bytes.u8(at + 2).toLong() shl 8) or bytes.u8(at + 3).toLong()
            if (bytes.hasAscii(at + 4, "acTL")) return true
            if (bytes.hasAscii(at + 4, "IDAT")) return false
            offset += 12 + size
        }
        return false
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.hasAscii(offset: Int, text: String): Boolean =
        offset + text.length <= size && text.indices.all { this[offset + it] == text[it].code.toByte() }
}

/** An animated WebP: the pack and the m.sticker event carry info.is_animated (Matrix spec ImageInfo). */
val StickerImportCandidate.isAnimated: Boolean
    get() = mimeType == "image/webp" && StickerFormat.isAnimatedWebP(bytes)
