// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Animated WebP in, animated PNG out (spec sticker-import, bipe-chat/bipe#840). StickerFormatTest.kt checks the same files.
struct StickerFormatTests {
    /// A RIFF chunk: type, little-endian size, payload, pad byte for an odd size.
    private func riff(_ type: String, _ size: Int) -> [UInt8] {
        Array(type.utf8) + [UInt8(size & 0xFF), UInt8(size >> 8 & 0xFF), 0, 0] + [UInt8](repeating: 0, count: size + (size & 1))
    }

    private func webp(frames: Int) -> Data {
        let body = Array("WEBP".utf8) + riff("VP8X", 10) + riff("ANIM", 6) + (0..<frames).flatMap { _ in riff("ANMF", 17) }
        return Data(Array("RIFF".utf8) + [UInt8(body.count & 0xFF), UInt8(body.count >> 8 & 0xFF), 0, 0] + body)
    }

    /// A PNG chunk: big-endian size, type, payload, CRC (not checked).
    private func png(_ types: [String]) -> Data {
        let signature: [UInt8] = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]
        return Data(signature + types.flatMap { [0, 0, 0, 4] + Array($0.utf8) + [0, 0, 0, 0] + [0, 0, 0, 0] })
    }

    @Test func formats() {
        let rows: [(Bool, Bool, String)] = [
            (StickerFormat.isAnimatedWebP(webp(frames: 3)), true, "three frames"),
            (StickerFormat.isAnimatedWebP(webp(frames: 1)), false, "one frame"),
            (StickerFormat.isAnimatedWebP(Data("RIFF".utf8)), false, "cut file"),
            (StickerFormat.isAnimatedPNG(png(["IHDR", "acTL", "IDAT"])), true, "APNG"),
            (StickerFormat.isAnimatedPNG(png(["IHDR", "IDAT", "acTL"])), false, "acTL after IDAT"),
            (StickerFormat.isAnimatedPNG(webp(frames: 3)), false, "not a PNG"),
        ]
        for (got, want, name) in rows {
            #expect(got == want, "\(name)")
        }
    }
}
