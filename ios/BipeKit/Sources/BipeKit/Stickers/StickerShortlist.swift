// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Sticker recents and favourites (spec sticker-picker, ADR 0205, bipe-chat/bipe#838). Global account data
// BipeRegistries.stickerRecentsEventType and stickerFavouritesEventType with content
// {"stickers": [{"pack": packId, "shortcode": shortcode}]}. Same rules as android/libraries/bipe stickers/StickerShortlist.kt.

/// One sticker in a list, by pack and shortcode.
public struct StickerRef: Hashable, Sendable {
    public let pack: String
    public let shortcode: String

    public init(pack: String, shortcode: String) {
        self.pack = pack
        self.shortcode = shortcode
    }

    public init(_ sticker: Sticker) {
        self.init(pack: sticker.packId, shortcode: sticker.shortcode)
    }
}

public enum StickerShortlist {
    /// The list in the account data content, in order, without duplicates. Empty on any other content.
    public static func parse(_ json: Data) -> [StickerRef] {
        guard let content = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let items = content["stickers"] as? [Any] else { return [] }
        var seen = Set<StickerRef>()
        return items.compactMap { item in
            guard let item = item as? [String: Any],
                  let pack = item["pack"] as? String, let shortcode = item["shortcode"] as? String else { return nil }
            let ref = StickerRef(pack: pack, shortcode: shortcode)
            return seen.insert(ref).inserted ? ref : nil
        }
    }

    /// The account data content for a list.
    public static func content(_ refs: [StickerRef]) -> Data {
        let items = refs.map { ["pack": $0.pack, "shortcode": $0.shortcode] }
        return (try? JSONSerialization.data(withJSONObject: ["stickers": items], options: [.sortedKeys])) ?? Data("{}".utf8)
    }

    /// A sent sticker goes first in recents, once, and the list keeps at most stickerRecentsMax.
    public static func pushRecent(_ ref: StickerRef, into recents: [StickerRef]) -> [StickerRef] {
        Array(([ref] + recents.filter { $0 != ref }).prefix(BipeRegistries.stickerRecentsMax))
    }

    /// Adds the sticker first in favourites, or removes it. At stickerFavouritesMax the oldest favourite goes.
    public static func toggleFavourite(_ ref: StickerRef, in favourites: [StickerRef]) -> [StickerRef] {
        if favourites.contains(ref) { return favourites.filter { $0 != ref } }
        return Array(([ref] + favourites).prefix(BipeRegistries.stickerFavouritesMax))
    }

    /// The stickers of the list that are in a loaded pack, in list order. The others drop out.
    public static func resolve(_ refs: [StickerRef], in packs: [StickerPack]) -> [Sticker] {
        var byRef: [StickerRef: Sticker] = [:]
        for sticker in packs.flatMap(\.stickers) where byRef[StickerRef(sticker)] == nil {
            byRef[StickerRef(sticker)] = sticker
        }
        return refs.compactMap { byRef[$0] }
    }
}
