// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// test-vectors/sticker-search.json (ADR 0205, bipe-chat/bipe#839). StickerSearchTest.kt runs the same file.
struct StickerSearchTests {
    @Test func fold() throws {
        for v in try SharedVectors.array("sticker-search.json", key: "fold") {
            #expect(StickerSearch.fold(v["input"] as? String ?? "") == v["expected"] as? String, "\(v["input"] ?? "")")
        }
    }

    @Test func emojiKey() throws {
        for v in try SharedVectors.array("sticker-search.json", key: "emoji_key") {
            #expect(StickerSearch.emojiKey(v["input"] as? String ?? "") == v["expected"] as? String, "\(v["input"] ?? "")")
        }
    }

    @Test func queryTags() throws {
        for v in try SharedVectors.array("sticker-search.json", key: "tags") {
            #expect(StickerSearch.queryTags(v["query"] as? String ?? "") == v["expected"] as? [String], "\(v["query"] ?? "")")
        }
    }

    @Test func matches() throws {
        for v in try SharedVectors.array("sticker-search.json", key: "matches") {
            let sticker = try #require(v["sticker"] as? [String: Any])
            let result = StickerSearch.matches(v["query"] as? String ?? "", body: sticker["body"] as? String ?? "",
                                               tags: sticker["tags"] as? [String] ?? [], pack: sticker["pack"] as? String)
            #expect(result == v["expected"] as? Bool, "\(v["name"] ?? "")")
        }
    }

    @Test func whatsAppEmojiBecomeTags() {
        let json = Array(#"{"sticker-pack-id":"x","emojis":["😂","🙏🏽","🐶"]}"#.utf8)
        let exif: [UInt8] = Array("Exif\0\0II".utf8) + json
        func le(_ n: Int) -> [UInt8] { [UInt8(n & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 24 & 0xFF)] }
        let vp8x: [UInt8] = Array("VP8X".utf8) + le(10) + [UInt8](repeating: 0, count: 10)
        var chunks = vp8x + Array("EXIF".utf8) + le(exif.count) + exif
        if exif.count % 2 == 1 { chunks.append(0) }
        let webp = Array("RIFF".utf8) + le(chunks.count + 4) + Array("WEBP".utf8) + chunks
        #expect(StickerImporter.emojiTags(webp: Data(webp)) == ["riso", "obrigado", "amem"])
        #expect(StickerImporter.emojiTags(webp: Data(Array("RIFF".utf8) + le(4) + Array("WEBP".utf8))).isEmpty)
    }
}
