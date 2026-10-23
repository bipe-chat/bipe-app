// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers
import Vision

/// Makes a 512 by 512 sticker from a photo on the device (spec sticker-from-photo): the subject cut out
/// with Vision on a transparent square (PNG), or a centred square crop when no subject is found.
public enum StickerMaker {
    public static func sticker(fromImageData data: Data) -> StickerImportCandidate? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048,
        ]
        guard let photo = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let image = cutOut(photo) ?? photo
        guard let square = squared(image, side: StickerImporter.stickerSide), let png = pngData(square) else { return nil }
        return StickerImporter.candidate(png)
    }

    /// The photo's foreground instances, on transparency, cropped to their bounds. nil without a subject.
    private static func cutOut(_ photo: CGImage) -> CGImage? {
        let request = VNGenerateForegroundInstanceMaskRequest()
        let handler = VNImageRequestHandler(cgImage: photo)
        guard (try? handler.perform([request])) != nil,
              let observation = request.results?.first,
              !observation.allInstances.isEmpty,
              let buffer = try? observation.generateMaskedImage(ofInstances: observation.allInstances,
                                                                from: handler,
                                                                croppedToInstancesExtent: true) else { return nil }
        let image = CIImage(cvPixelBuffer: buffer)
        return CIContext().createCGImage(image, from: image.extent)
    }

    /// Fits the image into a transparent square: a centred crop for a photo, the whole subject for a cut-out.
    private static func squared(_ image: CGImage, side: Int) -> CGImage? {
        guard let context = CGContext(data: nil,
                                      width: side,
                                      height: side,
                                      bitsPerComponent: 8,
                                      bytesPerRow: side * 4,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let width = CGFloat(image.width)
        let height = CGFloat(image.height)
        let hasAlpha = image.alphaInfo != .none && image.alphaInfo != .noneSkipLast && image.alphaInfo != .noneSkipFirst
        // A cut-out fits whole; a photo fills the square from its centre.
        let scale = hasAlpha ? CGFloat(side) / max(width, height) : CGFloat(side) / min(width, height)
        let drawn = CGSize(width: width * scale, height: height * scale)
        let origin = CGPoint(x: (CGFloat(side) - drawn.width) / 2, y: (CGFloat(side) - drawn.height) / 2)
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(origin: origin, size: drawn))
        return context.makeImage()
    }

    private static func pngData(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}
