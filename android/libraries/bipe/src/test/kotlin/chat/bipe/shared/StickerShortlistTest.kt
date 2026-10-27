// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries.STICKER_FAVOURITES_MAX
import chat.bipe.shared.generated.BipeRegistries.STICKER_RECENTS_MAX
import chat.bipe.stickers.Sticker
import chat.bipe.stickers.StickerPack
import chat.bipe.stickers.StickerRef
import chat.bipe.stickers.StickerShortlist.content
import chat.bipe.stickers.StickerShortlist.parse
import chat.bipe.stickers.StickerShortlist.pushRecent
import chat.bipe.stickers.StickerShortlist.resolve
import chat.bipe.stickers.StickerShortlist.toggleFavourite
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recents and favourites (spec sticker-picker, bipe-chat/bipe#838). StickerShortlistTests.swift checks the same cases. */
class StickerShortlistTest {
    private val refs = (0..999).map { StickerRef("user", "st_$it") }

    /** Each row: what the rule gave, what the spec wants, and the rule's name. */
    @Test
    fun shortlistRules() {
        val fullRecents = refs.take(STICKER_RECENTS_MAX)
        val fullFavourites = refs.take(STICKER_FAVOURITES_MAX)
        val sticker = Sticker("user", "st_1", "mxc://x/1", "a", null, null, null, null)
        val json = """{"stickers":[{"pack":"user","shortcode":"st_1"},{"pack":"user"},{"pack":"user","shortcode":"st_1"},{"pack":"!r:x/","shortcode":"st_2"}]}"""
        listOf(
            Triple(parse(json), listOf(refs[1], StickerRef("!r:x/", "st_2")), "parse keeps order, drops junk and duplicates"),
            Triple(parse("[]"), emptyList<StickerRef>(), "parse of other content"),
            Triple(parse(content(listOf(refs[2], refs[1]))), listOf(refs[2], refs[1]), "content round trip"),
            Triple(pushRecent(refs[5], fullRecents), listOf(refs[5]) + fullRecents.filter { it != refs[5] }, "recent goes first once"),
            Triple(pushRecent(refs[999], fullRecents), listOf(refs[999]) + fullRecents.dropLast(1), "recents cap"),
            Triple(toggleFavourite(refs[1], listOf(refs[2])), listOf(refs[1], refs[2]), "favourite added first"),
            Triple(toggleFavourite(refs[2], listOf(refs[1], refs[2])), listOf(refs[1]), "favourite removed"),
            Triple(toggleFavourite(refs[999], fullFavourites), listOf(refs[999]) + fullFavourites.dropLast(1), "oldest favourite goes"),
            Triple(resolve(listOf(refs[3], refs[1]), listOf(StickerPack(StickerPack.Source.User, null, listOf(sticker)))), listOf(sticker), "resolve drops stickers in no pack"),
        ).forEach { (actual, expected, rule) -> assertEquals(rule, expected, actual) }
    }
}
