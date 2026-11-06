// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import Foundation

/// Match the web's explicit byte segment. Core Image optimizes MATRIX into an alphanumeric segment,
/// which released iPhone scanners reject. No text conversion, network call or QR secret is logged here.
enum MacQRCode {
    static func image(_ payload: Data) -> NSImage? {
        guard !payload.isEmpty, payload.count <= 2953 else { return nil }
        var input = [UInt8](repeating: 0, count: Int(qrcodegen_BUFFER_LEN_MAX))
        var output = input
        input.replaceSubrange(0..<payload.count, with: payload)
        guard qrcodegen_encodeBinary(&input, payload.count, &output, qrcodegen_Ecc_LOW,
                                    1, 40, qrcodegen_Mask_AUTO, false) else { return nil }
        let side = Int(qrcodegen_getSize(output))
        let quiet = 4
        let scale = 8
        let pixels = (side + quiet * 2) * scale
        guard let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels,
                                            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true,
                                            isPlanar: false, colorSpaceName: .deviceRGB,
                                            bytesPerRow: pixels * 4, bitsPerPixel: 32),
              let bytes = bitmap.bitmapData else { return nil }
        for y in 0..<pixels {
            for x in 0..<pixels {
                let mx = x / scale - quiet, my = y / scale - quiet
                let dark = mx >= 0 && my >= 0 && mx < side && my < side
                    && qrcodegen_getModule(output, Int32(mx), Int32(my))
                let value: UInt8 = dark ? 0 : 255
                let index = y * bitmap.bytesPerRow + x * 4
                bytes[index] = value
                bytes[index + 1] = value
                bytes[index + 2] = value
                bytes[index + 3] = 255
            }
        }
        let image = NSImage(size: NSSize(width: pixels, height: pixels))
        image.addRepresentation(bitmap)
        return image
    }
}
