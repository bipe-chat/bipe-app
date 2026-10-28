// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Reads the container of a sticker file without decoding it (ADR 0205 rule 2: animated WebP is the one
/// animated format). Same rules as android/libraries/bipe stickers/StickerFormat.kt.
public enum StickerFormat {
    /// true for a WebP with more than one frame (ANMF chunks of the RIFF container).
    public static func isAnimatedWebP(_ data: Data) -> Bool {
        let bytes = [UInt8](data)
        guard bytes.count >= 12, bytes[0..<4].elementsEqual("RIFF".utf8), bytes[8..<12].elementsEqual("WEBP".utf8) else { return false }
        var frames = 0
        var offset = 12
        while offset + 8 <= bytes.count {
            let size = Int(bytes[offset + 4]) | Int(bytes[offset + 5]) << 8 | Int(bytes[offset + 6]) << 16 | Int(bytes[offset + 7]) << 24
            if bytes[offset..<offset + 4].elementsEqual("ANMF".utf8) {
                frames += 1
                if frames > 1 { return true }
            }
            offset += 8 + size + (size & 1)
        }
        return false
    }

    /// true for an animated PNG: an acTL chunk before the first IDAT. The import rejects it (spec sticker-import).
    public static func isAnimatedPNG(_ data: Data) -> Bool {
        let bytes = [UInt8](data)
        guard bytes.count >= 8, bytes[0..<8].elementsEqual([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) else { return false }
        var offset = 8
        while offset + 8 <= bytes.count {
            let size = Int(bytes[offset]) << 24 | Int(bytes[offset + 1]) << 16 | Int(bytes[offset + 2]) << 8 | Int(bytes[offset + 3])
            let type = bytes[offset + 4..<offset + 8]
            if type.elementsEqual("acTL".utf8) { return true }
            if type.elementsEqual("IDAT".utf8) { return false }
            offset += 12 + size
        }
        return false
    }
}

extension StickerImportCandidate {
    /// An animated WebP: the pack and the m.sticker event carry info.is_animated (Matrix spec ImageInfo).
    public var isAnimated: Bool { mimeType == "image/webp" && StickerFormat.isAnimatedWebP(data) }

    /// The info of the image in the pack: w, h, mimetype, size, and is_animated for an animated WebP.
    var info: [String: Any] {
        var info: [String: Any] = ["w": width, "h": height, "mimetype": mimeType, "size": data.count]
        if isAnimated { info["is_animated"] = true }
        return info
    }
}
