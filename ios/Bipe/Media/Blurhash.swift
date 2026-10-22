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

    /// Encodes an image as a blurhash with 4 by 3 components, for the info of an upload.
    static func encode(_ image: CGImage, componentsX: Int = 4, componentsY: Int = 3) -> String? {
        let width = 32, height = 32
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        guard let context = CGContext(data: &pixels,
                                      width: width,
                                      height: height,
                                      bitsPerComponent: 8,
                                      bytesPerRow: width * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        var factors: [(Double, Double, Double)] = []
        for j in 0..<componentsY {
            for i in 0..<componentsX {
                let normalisation = (i == 0 && j == 0) ? 1.0 : 2.0
                var r = 0.0, g = 0.0, b = 0.0
                for y in 0..<height {
                    for x in 0..<width {
                        let basis = normalisation * cos(Double.pi * Double(i * x) / Double(width)) * cos(Double.pi * Double(j * y) / Double(height))
                        let offset = (y * width + x) * 4
                        r += basis * linear(Int(pixels[offset]))
                        g += basis * linear(Int(pixels[offset + 1]))
                        b += basis * linear(Int(pixels[offset + 2]))
                    }
                }
                let scale = 1.0 / Double(width * height)
                factors.append((r * scale, g * scale, b * scale))
            }
        }
        let dc = factors[0]
        let ac = factors.dropFirst()
        var hash = base83(componentsX - 1 + (componentsY - 1) * 9, length: 1)
        let maximum: Double
        if ac.isEmpty {
            maximum = 1
            hash += base83(0, length: 1)
        } else {
            let actual = ac.map { max(abs($0.0), abs($0.1), abs($0.2)) }.max() ?? 0
            let quantised = Int(max(0, min(82, (actual * 166 - 0.5).rounded(.down))))
            maximum = Double(quantised + 1) / 166
            hash += base83(quantised, length: 1)
        }
        hash += base83(Int(srgb(dc.0)) << 16 | Int(srgb(dc.1)) << 8 | Int(srgb(dc.2)), length: 4)
        for factor in ac {
            func quantise(_ value: Double) -> Int {
                let unit = value / maximum
                let root = (unit < 0 ? -1 : 1) * pow(abs(unit), 0.5)
                return Int(max(0, min(18, (root * 9 + 9.5).rounded(.down))))
            }
            hash += base83(quantise(factor.0) * 19 * 19 + quantise(factor.1) * 19 + quantise(factor.2), length: 2)
        }
        return hash
    }

    private static func base83(_ value: Int, length: Int) -> String {
        var result = ""
        for index in 1...length {
            let digit = (value / Int(pow(83.0, Double(length - index)))) % 83
            result.append(Self.digits[digit])
        }
        return result
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
