// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Sticker search on the phone (ADR 0205 rule 3, spec sticker-picker, bipe-chat/bipe#839). The rules are the
// reference rules of bipe-shared tools/lib/registries.mjs; test-vectors/sticker-search.json checks them. This file
// has no network code: search reads only the packs that are already loaded. StickerSearch.kt is the same on Android.
public enum StickerSearch {
    /// NFKD, no marks, lower case, every character that is not a letter or a digit is a space, every run of one
    /// repeated letter is one letter (kkkk is k), spaces collapsed and trimmed.
    public static func fold(_ text: String) -> String {
        text.decomposedStringWithCompatibilityMapping
            .replacingOccurrences(of: "\\p{M}", with: "", options: .regularExpression)
            .lowercased()
            .replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "(\\p{L})\\1+", with: "$1", options: .regularExpression)
            .replacingOccurrences(of: " +", with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: " "))
    }

    /// An emoji query without variation selectors (U+FE0F) and skin tones (U+1F3FB to U+1F3FF), trimmed.
    public static func emojiKey(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: text.unicodeScalars.filter { $0.value != 0xFE0F && !(0x1F3FB...0x1F3FF).contains($0.value) })
        return String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The tag ids a query asks for, in registry order. An emoji maps through stickerEmoji. A word maps through
    /// stickerWords and the tag ids (bom-dia is "bom dia"): equal, or a prefix from stickerPrefixMinLength characters.
    public static func queryTags(_ query: String) -> [String] {
        var found = Set<String>()
        if let emoji = BipeRegistries.stickerEmoji[emojiKey(query)] {
            found.formUnion(emoji)
        } else {
            let q = fold(query)
            if !q.isEmpty {
                let hits = { (word: String) in word == q || (q.utf16.count >= BipeRegistries.stickerPrefixMinLength && word.hasPrefix(q)) }
                for (word, tags) in BipeRegistries.stickerWords where hits(word) { found.formUnion(tags) }
                for tag in BipeRegistries.stickerTags where hits(fold(tag)) { found.insert(tag) }
            }
        }
        return BipeRegistries.stickerTags.filter(found.contains)
    }

    /// A shared tag, or the folded query at the start of a word of the folded body or pack name.
    public static func matches(_ query: String, body: String, tags: [String], pack: String?) -> Bool {
        let wanted = queryTags(query)
        if tags.contains(where: wanted.contains) { return true }
        let q = fold(query)
        guard !q.isEmpty else { return false }
        return [body, pack].compactMap { $0 }.contains { " \(fold($0))".contains(" \(q)") }
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
