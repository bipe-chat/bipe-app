// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.stickers.Sticker
import chat.bipe.stickers.StickerPack
import chat.bipe.stickers.StickerRef
import chat.bipe.stickers.StickerShortlist
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Recents and favourites (spec sticker-picker, bipe-chat/bipe#838). StickerShortlistTests.swift checks the same cases. */
class StickerShortlistTest {
    private fun ref(n: Int) = StickerRef("user", "st_$n")

    @Test
    fun parseKeepsOrderDropsDuplicatesAndJunk() {
        val json = """{"stickers":[{"pack":"user","shortcode":"st_1"},{"pack":"user"},{"pack":"user","shortcode":"st_1"},{"pack":"!r:x/","shortcode":"st_2"}]}"""
        assertThat(StickerShortlist.parse(json)).containsExactly(ref(1), StickerRef("!r:x/", "st_2")).inOrder()
        assertThat(StickerShortlist.parse("[]")).isEmpty()
    }

    @Test
    fun contentRoundTrips() {
        val list = listOf(ref(2), ref(1))
        assertThat(StickerShortlist.parse(StickerShortlist.content(list))).isEqualTo(list)
    }

    @Test
    fun recentGoesFirstOnceAndCaps() {
        val full = (0 until BipeRegistries.STICKER_RECENTS_MAX).map(::ref)
        val pushed = StickerShortlist.pushRecent(ref(5), full)
        assertThat(pushed.first()).isEqualTo(ref(5))
        assertThat(pushed.count { it == ref(5) }).isEqualTo(1)
        val fresh = StickerShortlist.pushRecent(ref(999), full)
        assertThat(fresh).hasSize(BipeRegistries.STICKER_RECENTS_MAX)
        assertThat(fresh.first()).isEqualTo(ref(999))
        assertThat(fresh).doesNotContain(full.last())
    }

    @Test
    fun favouriteToggleAddsFirstRemovesAndDropsOldest() {
        assertThat(StickerShortlist.toggleFavourite(ref(1), listOf(ref(2)))).containsExactly(ref(1), ref(2)).inOrder()
        assertThat(StickerShortlist.toggleFavourite(ref(2), listOf(ref(1), ref(2)))).containsExactly(ref(1))
        val full = (0 until BipeRegistries.STICKER_FAVOURITES_MAX).map(::ref)
        val added = StickerShortlist.toggleFavourite(ref(999), full)
        assertThat(added).hasSize(BipeRegistries.STICKER_FAVOURITES_MAX)
        assertThat(added.first()).isEqualTo(ref(999))
        assertThat(added).doesNotContain(full.last())
    }

    @Test
    fun resolveDropsStickersThatAreInNoPack() {
        val sticker = Sticker("user", "st_1", "mxc://x/1", "a", null, null, null, null)
        val pack = StickerPack(StickerPack.Source.User, null, listOf(sticker))
        assertThat(StickerShortlist.resolve(listOf(ref(3), ref(1)), listOf(pack))).containsExactly(sticker)
    }
}
