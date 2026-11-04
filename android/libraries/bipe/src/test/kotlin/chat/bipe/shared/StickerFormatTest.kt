// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.stickers.StickerFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/** Animated WebP in, animated PNG out (spec sticker-import, bipe-chat/bipe#840). StickerFormatTests.swift checks the same files. */
class StickerFormatTest {
    /** A RIFF chunk: type, little-endian size, payload, pad byte for an odd size. */
    private fun riff(type: String, size: Int): ByteArray =
        type.toByteArray() + byteArrayOf((size and 0xFF).toByte(), (size shr 8 and 0xFF).toByte(), 0, 0) + ByteArray(size + (size and 1))

    private fun webp(frames: Int): ByteArray {
        var body = "WEBP".toByteArray() + riff("VP8X", 10) + riff("ANIM", 6)
        repeat(frames) { body += riff("ANMF", 17) }
        return "RIFF".toByteArray() + byteArrayOf((body.size and 0xFF).toByte(), (body.size shr 8 and 0xFF).toByte(), 0, 0) + body
    }

    /** PNG chunks: big-endian size, type, payload, CRC (not checked). */
    private fun png(vararg types: String): ByteArray {
        var bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (type in types) bytes += byteArrayOf(0, 0, 0, 4) + type.toByteArray() + ByteArray(8)
        return bytes
    }

    /** Each row: what the rule gave, what the spec wants, and the case. */
    @Test
    fun formats() {
        val rows = listOf(
            Triple(StickerFormat.isAnimatedWebP(webp(3)), true, "three frames"),
            Triple(StickerFormat.isAnimatedWebP(webp(1)), false, "one frame"),
            Triple(StickerFormat.isAnimatedWebP("RIFF".toByteArray()), false, "cut file"),
            Triple(StickerFormat.isAnimatedPng(png("IHDR", "acTL", "IDAT")), true, "APNG"),
            Triple(StickerFormat.isAnimatedPng(png("IHDR", "IDAT", "acTL")), false, "acTL after IDAT"),
            Triple(StickerFormat.isAnimatedPng(webp(3)), false, "not a PNG"),
        )
        for ((got, want, name) in rows) assertEquals(name, want, got)
    }
}
