// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import UIKit

/// Decodes the blurred placeholder of a media event (the blurhash format: base 83 digits holding
/// a DC colour and AC cosine components). The result is a small image that the row scales up.
enum Blurhash {
    private static let digits = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~")
    private static let digitValues: [Character: Int] = Dictionary(uniqueKeysWithValues: digits.enumerated().map { ($1, $0) })

    static func image(_ hash: String, width: Int = 32, height: Int = 32) -> UIImage? {
        guard let pixels = decode(hash, width: width, height: height) else { return nil }
        let data = Data(pixels) as CFData
        guard let provider = CGDataProvider(data: data),
              let cgImage = CGImage(width: width,
                                    height: height,
                                    bitsPerComponent: 8,
                                    bitsPerPixel: 24,
                                    bytesPerRow: width * 3,
                                    space: CGColorSpaceCreateDeviceRGB(),
                                    bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                                    provider: provider,
                                    decode: nil,
                                    shouldInterpolate: true,
                                    intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cgImage)
    }

    /// RGB bytes, row by row.
    static func decode(_ hash: String, width: Int, height: Int) -> [UInt8]? {
        let chars = Array(hash)
        guard chars.count >= 6, let sizeFlag = value(chars[0...0]) else { return nil }
        let componentsY = sizeFlag / 9 + 1
        let componentsX = sizeFlag % 9 + 1
        guard chars.count == 4 + 2 * componentsX * componentsY,
              let quantisedMax = value(chars[1...1]),
              let dc = value(chars[2...5]) else { return nil }
        let maxValue = Double(quantisedMax + 1) / 166
        var colors: [(Double, Double, Double)] = [(linear(dc >> 16), linear((dc >> 8) & 255), linear(dc & 255))]
        for index in 1..<(componentsX * componentsY) {
            let start = 4 + index * 2
            guard let ac = value(chars[start..<(start + 2)]) else { return nil }
            colors.append((signedSquare(Double(ac / (19 * 19)) - 9, maxValue),
                           signedSquare(Double((ac / 19) % 19) - 9, maxValue),
                           signedSquare(Double(ac % 19) - 9, maxValue)))
        }
        var pixels = [UInt8](repeating: 0, count: width * height * 3)
        for y in 0..<height {
            for x in 0..<width {
                var r = 0.0, g = 0.0, b = 0.0
                for j in 0..<componentsY {
                    let cosY = cos(Double.pi * Double(y * j) / Double(height))
                    for i in 0..<componentsX {
                        let basis = cos(Double.pi * Double(x * i) / Double(width)) * cosY
                        let color = colors[i + j * componentsX]
                        r += color.0 * basis
                        g += color.1 * basis
                        b += color.2 * basis
                    }
                }
                let offset = (y * width + x) * 3
                pixels[offset] = srgb(r)
                pixels[offset + 1] = srgb(g)
                pixels[offset + 2] = srgb(b)
            }
        }
        return pixels
    }

    private static func value<S: Sequence>(_ chars: S) -> Int? where S.Element == Character {
        var result = 0
        for char in chars {
            guard let digit = digitValues[char] else { return nil }
            result = result * 83 + digit
        }
        return result
    }

    private static func signedSquare(_ quantised: Double, _ maxValue: Double) -> Double {
        let unit = quantised / 9
        return (unit < 0 ? -1 : 1) * unit * unit * maxValue
    }

    private static func linear(_ value: Int) -> Double {
        let v = Double(value) / 255
        return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
    }

    private static func srgb(_ value: Double) -> UInt8 {
        let v = max(0, min(1, value))
        let encoded = v <= 0.0031308 ? v * 12.92 : 1.055 * pow(v, 1 / 2.4) - 0.055
        return UInt8(max(0, min(255, (encoded * 255).rounded())))
    }
}
