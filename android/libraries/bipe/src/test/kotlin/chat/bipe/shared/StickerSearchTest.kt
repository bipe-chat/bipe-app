// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.stickers.StickerImporter
import chat.bipe.stickers.StickerSearch
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/** test-vectors/sticker-search.json (ADR 0205, bipe-chat/bipe#839). StickerSearchTests.swift runs the same file. */
class StickerSearchTest {
    private val vectors: JsonObject =
        Json.parseToJsonElement(File(File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors"), "sticker-search.json").readText()).jsonObject

    private fun cases(key: String) = (vectors.getValue(key) as JsonArray).map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.strings(key: String) = get(key)?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun fold() = cases("fold").forEach { v ->
        assertWithMessage(v.text("input")).that(StickerSearch.fold(v.text("input"))).isEqualTo(v.text("expected"))
    }

    @Test
    fun emojiKey() = cases("emoji_key").forEach { v ->
        assertWithMessage(v.text("input")).that(StickerSearch.emojiKey(v.text("input"))).isEqualTo(v.text("expected"))
    }

    @Test
    fun queryTags() = cases("tags").forEach { v ->
        assertWithMessage(v.text("query")).that(StickerSearch.queryTags(v.text("query"))).isEqualTo(v.strings("expected"))
    }

    @Test
    fun matches() = cases("matches").forEach { v ->
        val sticker = v.getValue("sticker").jsonObject
        val result = StickerSearch.matches(v.text("query"), sticker.text("body"), sticker.strings("tags"), sticker["pack"]?.jsonPrimitive?.content)
        assertWithMessage(v.text("name")).that(result).isEqualTo(v.getValue("expected").jsonPrimitive.boolean)
    }

    @Test
    fun whatsAppEmojiBecomeTags() {
        fun le(n: Int) = byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte())
        val json = """{"sticker-pack-id":"x","emojis":["😂","🙏🏽","🐶"]}""".toByteArray()
        val exif = "Exif\u0000\u0000II".toByteArray() + json
        var chunks = "VP8X".toByteArray() + le(10) + ByteArray(10) + "EXIF".toByteArray() + le(exif.size) + exif
        if (exif.size % 2 == 1) chunks += byteArrayOf(0)
        val webp = "RIFF".toByteArray() + le(chunks.size + 4) + "WEBP".toByteArray() + chunks
        assertThat(StickerImporter.emojiTags(webp)).containsExactly("riso", "obrigado", "amem").inOrder()
        assertThat(StickerImporter.emojiTags("RIFF".toByteArray() + le(4) + "WEBP".toByteArray())).isEmpty()
    }
}
