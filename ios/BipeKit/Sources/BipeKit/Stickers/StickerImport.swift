// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation
import ImageIO

/// A sticker file ready to go into the user's pack.
public struct StickerImportCandidate: Equatable, Sendable {
    public let data: Data
    public let mimeType: String
    public let width: Int
    public let height: Int
    /// st_ plus the first 8 bytes of the SHA-256 in hex: the same file imported twice is one sticker.
    public let shortcode: String
}

/// Imports stickers from a WhatsApp chat export or from loose image files (ADR 0029, ADR 0033).
/// Same limits as android/libraries/bipe stickers/StickerImport.kt.
public enum StickerImporter {
    /// WhatsApp stickers are 512 by 512, static up to 100 KB, animated up to 500 KB. These limits leave room.
    public static let maxFileSize = BipeRegistries.stickerImportMaxBytes
    public static let maxSide = BipeRegistries.stickerImportMaxPixels
    public static let stickerSide = 512

    /// Sticker files from one picked file: a ZIP gives its .webp entries; a WebP or PNG file gives itself.
    /// Files that fail a check are skipped.
    public static func candidates(fileName: String?, data: Data) -> [StickerImportCandidate] {
        if fileName?.lowercased().hasSuffix(".zip") == true || data.starts(with: [0x50, 0x4B, 0x03, 0x04]) {
            let entries = (try? ZipReader(data: data).entries()) ?? []
            return entries
                .filter { $0.name.lowercased().hasSuffix(".webp") && !$0.isDirectory && $0.uncompressedSize <= maxFileSize }
                .compactMap { try? $0.read() }
                .compactMap(candidate)
        }
        return candidate(data).map { [$0] } ?? []
    }

    /// Checks the format by its magic bytes and the size with ImageIO. nil when the file is not a usable sticker.
    /// Animated WebP passes; an animated PNG does not (ADR 0205: animated WebP is the one animated format).
    public static func candidate(_ data: Data) -> StickerImportCandidate? {
        guard data.count <= maxFileSize, let mimeType = mimeType(data), !StickerFormat.isAnimatedPNG(data),
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              (1...maxSide).contains(width), (1...maxSide).contains(height) else { return nil }
        let digest = SHA256.hash(data: data).prefix(8).map { String(format: "%02x", $0) }.joined()
        return StickerImportCandidate(data: data, mimeType: mimeType, width: width, height: height, shortcode: "st_\(digest)")
    }

    private static func mimeType(_ data: Data) -> String? {
        let bytes = [UInt8](data.prefix(12))
        guard bytes.count == 12 else { return nil }
        if bytes[0..<4].elementsEqual("RIFF".utf8), bytes[8..<12].elementsEqual("WEBP".utf8) { return "image/webp" }
        if bytes[0..<8].elementsEqual([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) { return "image/png" }
        return nil
    }
}
