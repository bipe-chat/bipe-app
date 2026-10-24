// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CoreGraphics
import Foundation

/// ADR 0049 photo match (bipe#597): the dHash of an avatar, kept on the phone only.
/// Ports of `photoHash`, `photoDistance` and `photoMatches` in bipe-shared tools/lib/registries.mjs.
public nonisolated enum BipePhotoMatch {
    /// The dHash of a grayscale grid (`photoMatchHeight` rows of `photoMatchWidth` values), 16 hex digits; `nil` for a wrong size.
    public static func hash(gray: [[UInt8]]) -> String? {
        let width = BipeRegistries.photoMatchWidth
        guard gray.count == BipeRegistries.photoMatchHeight, gray.allSatisfy({ $0.count == width }) else { return nil }
        var value: UInt64 = 0
        for row in gray {
            for x in 0..<(width - 1) {
                value = (value << 1) | (row[x] < row[x + 1] ? 1 : 0)
            }
        }
        return String(format: "%016llx", value)
    }

    /// Hamming distance of two hashes; `Int.max` when one is not a hash.
    public static func distance(_ a: String, _ b: String) -> Int {
        guard let x = UInt64(a, radix: 16), let y = UInt64(b, radix: 16) else { return .max }
        return (x ^ y).nonzeroBitCount
    }

    public static func matches(_ a: String, _ b: String) -> Bool {
        distance(a, b) <= BipeRegistries.photoMatchMaxDistance
    }

    /// The grid of an avatar: area-averaged into width x height pixels, then luma 0.299 R + 0.587 G + 0.114 B.
    public static func grid(of image: CGImage) -> [[UInt8]]? {
        let width = BipeRegistries.photoMatchWidth
        let height = BipeRegistries.photoMatchHeight
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        // CGContext rows start at the top of the image in memory.
        return (0..<height).map { y in
            (0..<width).map { x in
                let i = (y * width + x) * 4
                let luma = 0.299 * Double(pixels[i]) + 0.587 * Double(pixels[i + 1]) + 0.114 * Double(pixels[i + 2])
                return UInt8(min(255, max(0, luma.rounded())))
            }
        }
    }
}
