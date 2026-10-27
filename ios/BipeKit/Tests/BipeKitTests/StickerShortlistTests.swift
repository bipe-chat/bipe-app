// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Recents and favourites (spec sticker-picker, bipe-chat/bipe#838). StickerShortlistTest.kt checks the same cases.
struct StickerShortlistTests {
    private func ref(_ n: Int) -> StickerRef { StickerRef(pack: "user", shortcode: "st_\(n)") }

    @Test func parseKeepsOrderDropsDuplicatesAndJunk() {
        let json = Data(#"{"stickers":[{"pack":"user","shortcode":"st_1"},{"pack":"user"},{"pack":"user","shortcode":"st_1"},{"pack":"!r:x/","shortcode":"st_2"}]}"#.utf8)
        #expect(StickerShortlist.parse(json) == [ref(1), StickerRef(pack: "!r:x/", shortcode: "st_2")])
        #expect(StickerShortlist.parse(Data("[]".utf8)).isEmpty)
    }

    @Test func contentRoundTrips() {
        let list = [ref(2), ref(1)]
        #expect(StickerShortlist.parse(StickerShortlist.content(list)) == list)
    }

    @Test func recentGoesFirstOnceAndCaps() {
        let full = (0..<BipeRegistries.stickerRecentsMax).map(ref)
        let pushed = StickerShortlist.pushRecent(ref(5), into: full)
        #expect(pushed.first == ref(5))
        #expect(pushed.filter { $0 == ref(5) }.count == 1)
        let fresh = StickerShortlist.pushRecent(ref(999), into: full)
        #expect(fresh.count == BipeRegistries.stickerRecentsMax)
        #expect(fresh.first == ref(999) && !fresh.contains(full.last!))
    }

    @Test func favouriteToggleAddsFirstRemovesAndDropsOldest() {
        #expect(StickerShortlist.toggleFavourite(ref(1), in: [ref(2)]) == [ref(1), ref(2)])
        #expect(StickerShortlist.toggleFavourite(ref(2), in: [ref(1), ref(2)]) == [ref(1)])
        let full = (0..<BipeRegistries.stickerFavouritesMax).map(ref)
        let added = StickerShortlist.toggleFavourite(ref(999), in: full)
        #expect(added.count == BipeRegistries.stickerFavouritesMax && added.first == ref(999) && !added.contains(full.last!))
    }

    @Test func resolveDropsStickersThatAreInNoPack() {
        let sticker = Sticker(packId: "user", shortcode: "st_1", url: "mxc://x/1", body: "a", width: nil, height: nil, mimeType: nil, size: nil)
        let pack = StickerPack(source: .user, displayName: nil, stickers: [sticker])
        #expect(StickerShortlist.resolve([ref(3), ref(1)], in: [pack]) == [sticker])
    }
}
