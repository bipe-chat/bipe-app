// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.stickers.Sticker
import chat.bipe.stickers.StickerPack
import chat.bipe.stickers.StickerPackPreview
import chat.bipe.stickers.StickerPackRef
import chat.bipe.stickers.StickerPackRoom
import chat.bipe.stickers.packRef
import chat.bipe.stickers.sendContentJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pack links, stickerPackField and pack rooms (spec sticker-picker, sticker-pack, bipe-chat/bipe#842).
 * StickerPackShareTests.swift checks the same cases.
 */
class StickerPackShareTest {
    private val ref = StickerPackRef("!abc:bipe.net.br", "")
    private val roomKey = StickerPackRef("!abc:bipe.net.br", "k")

    private fun sticker(packId: String) = Sticker(packId, "s", "mxc://x/1", "a", width = null, height = null, mimeType = null, size = null)

    /** Each row: what the rule gave, what the spec wants, and the case. */
    @Test
    fun packShareRules() {
        val state = """[{"type":"m.room.create","state_key":"","sender":"@ana:x","content":{}},""" +
            """{"type":"m.room.power_levels","state_key":"","content":{"users":{"@bia:x":100},"state_default":50}},""" +
            """{"type":"im.ponies.room_emotes","state_key":"","content":{"pack":{"display_name":"P"},"images":{"s":{"url":"mxc://x/1"}}}}]"""
        val enabled = StickerPackRoom.enabling(ref, Json.parseToJsonElement("""{"rooms":{"!old:x":{"":{}}},"other":1}""") as JsonObject)
        val body = StickerPackRoom.createBody(Json.parseToJsonElement("""{"images":{"s":{"url":"mxc://x/1"}}}""") as JsonObject, "Ana")
        val initialState = (body["initial_state"] as JsonArray).map { it.toString() }
        val preview = StickerPackPreview.parse(state, StickerPackRef("!r:x", ""))
        val rows = listOf(
            Triple(ref.link, "https://bipe.chat/s/%21abc%3Abipe.net.br", "link"),
            Triple(StickerPackRef.fromLink(ref.link), ref, "link round trip"),
            Triple(StickerPackRef.fromLink("https://www.bipe.chat/s/!abc:bipe.net.br"), ref, "www host"),
            Triple(StickerPackRef.fromLink("https://bipe.chat/@ana"), null, "profile link"),
            Triple(StickerPackRef.fromLink("https://example.com/s/%21abc%3Abipe.net.br"), null, "other host"),
            Triple(StickerPackRef.fromLink("https://bipe.chat/s/abc"), null, "not a room ID"),
            Triple(
                StickerPackRef.read("""{"type":"m.sticker","content":{"body":"a","chat.bipe.pack":{"room_id":"!abc:bipe.net.br","state_key":"k"}}}"""),
                roomKey,
                "pack field",
            ),
            Triple(StickerPackRef.read("""{"content":{"chat.bipe.pack":{"room_id":"abc","state_key":""}}}"""), null, "bad room ID"),
            Triple(StickerPackRef.read("""{"content":{"body":"a"}}"""), null, "no pack field"),
            Triple(StickerPackRef.read(sticker("!abc:bipe.net.br/k").sendContentJson()), roomKey, "sent from a room pack"),
            Triple(sticker("user").packRef, null, "own pack"),
            Triple(sticker("user").sendContentJson(), sticker("user").eventContentJson(), "own pack content"),
            Triple(StickerPack.parseEnabledRooms(enabled.toString()), listOf("!abc:bipe.net.br" to "", "!old:x" to ""), "enabling"),
            Triple(enabled["other"], JsonPrimitive(1), "enabling keeps fields"),
            Triple(body["preset"], JsonPrimitive("public_chat"), "preset"),
            Triple(body["name"], JsonPrimitive("Ana"), "name"),
            Triple(initialState.any { "world_readable" in it }, true, "world_readable"),
            Triple(initialState.any { "m.room.encryption" in it }, false, "no encryption"),
            Triple(StickerPackPreview.canWrite(state, "@ana:x"), true, "creator writes"),
            Triple(StickerPackPreview.canWrite(state, "@bia:x"), true, "power level writes"),
            Triple(StickerPackPreview.canWrite(state, "@caio:x"), false, "member does not write"),
            Triple(preview?.creatorId, "@ana:x", "creator"),
            Triple(preview?.pack?.displayName, "P", "pack name"),
            Triple(preview?.pack?.stickers?.size, 1, "stickers"),
        )
        for ((got, want, name) in rows) assertEquals(name, want, got)
    }
}
