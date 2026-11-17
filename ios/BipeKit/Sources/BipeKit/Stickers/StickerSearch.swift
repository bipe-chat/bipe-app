// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

// Sticker search on the phone (ADR 0205 rule 3, spec sticker-picker, bipe-chat/bipe#839). The rules are the
// reference rules of bipe-shared tools/lib/registries.mjs; test-vectors/sticker-search.json checks them. This file
// has no network code: search reads only the packs that are already loaded. StickerSearch.kt is the same on Android.
public enum StickerSearch {
    /// bipe-core `stickerSearchFold`: NFKD, no marks, lower case, every character that is not a letter or a digit is a
    /// space, every run of one repeated letter is one letter (kkkk is k), spaces collapsed and trimmed.
    public static func fold(_ text: String) -> String {
        stickerSearchFold(text: text)
    }

    /// bipe-core `stickerEmojiKey`: without variation selectors and skin tones, trimmed.
    public static func emojiKey(_ text: String) -> String {
        stickerEmojiKey(text: text)
    }

    /// bipe-core `stickerQueryTags`: the tag ids a query asks for, in registry order.
    public static func queryTags(_ query: String) -> [String] {
        stickerQueryTags(query: query)
    }

    /// bipe-core `stickerMatches`: a shared tag, or the folded query at the start of a word of the body or pack name.
    public static func matches(_ query: String, body: String, tags: [String], pack: String?) -> Bool {
        stickerMatches(query: query, body: body, tags: tags, pack: pack)
    }

    /// State results of spec sticker-picker: the matching stickers, first, then later lists, without duplicates.
    public static func results(_ query: String, in lists: [[Sticker]], packTitle: (String) -> String?) -> [Sticker] {
        var seen = Set<String>()
        return lists.joined().filter { sticker in
            guard !seen.contains(sticker.id),
                  matches(query, body: sticker.body, tags: sticker.tags, pack: packTitle(sticker.packId)) else { return false }
            seen.insert(sticker.id)
            return true
        }
    }
}
