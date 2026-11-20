// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.chat

import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.stickers.StickerImportCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerImportCandidateTest {
    @Test fun theNativeProbeRunsOnlyForAnAcceptedFormatAndSize() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val animatedPng = png + ByteArray(4) + "acTL".toByteArray()
        assertNull(StickerImportCandidate.create(animatedPng) { error("Animated PNG must be rejected first") })
        assertNull(StickerImportCandidate.create(ByteArray(BipeRegistries.STICKER_IMPORT_MAX_BYTES + 1)) { error("Too large") })
        assertNull(StickerImportCandidate.create(webp("{}")) { BipeRegistries.STICKER_IMPORT_MAX_PIXELS + 1 to 10 })
    }

    @Test fun whatsappMetadataUsesSharedTagsAndStableAliases() {
        val bytes = webp("""{"emojis":["🙏","😂","😂"]}""")
        val imported = checkNotNull(StickerImportCandidate.create(bytes) { 32 to 64 })
        assertEquals("image/webp", imported.mimeType)
        assertEquals(listOf("riso", "obrigado", "amem"), imported.tags)
        assertEquals(imported.shortcode, StickerImportCandidate.create(bytes.copyOf()) { 32 to 64 }?.shortcode)
    }

    private fun webp(metadata: String): ByteArray {
        val exif = metadata.toByteArray()
        val padding = exif.size % 2
        return ByteBuffer.allocate(20 + exif.size + padding).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(12 + exif.size + padding).put("WEBP".toByteArray())
            .put("EXIF".toByteArray()).putInt(exif.size).put(exif).array()
    }
}
