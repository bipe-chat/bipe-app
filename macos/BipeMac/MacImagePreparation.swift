// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import ImageIO
import MatrixRustSDK
import UniformTypeIdentifiers

struct MacPreparedImage: Identifiable {
    let id = UUID()
    let folder: URL
    let file: URL
    let thumbnail: URL
    let info: ImageInfo
    func remove() { try? FileManager.default.removeItem(at: folder) }
}

/// The phone's standard photo policy: oriented, at most 2048px, JPEG, without camera/location metadata.
enum MacImagePreparation {
    static func decode(_ data: Data, maxPixel: Int) throws -> CGImage {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = downsample(source, maxPixel: maxPixel) else { throw CocoaError(.fileReadCorruptFile) }
        return image
    }

    static func prepare(_ url: URL, highQuality: Bool = false) throws -> MacPreparedImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = downsample(source, maxPixel: 2048) else { throw CocoaError(.fileReadCorruptFile) }
        let count = CGImageSourceGetCount(source)
        let animated = CGImageSourceGetType(source).map { UTType($0 as String) == .gif } == true && count > 1
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("chat.bipe.app.mac/Uploads/" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
                                               attributes: [.posixPermissions: 0o700])
        do {
            var excluded = folder
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try excluded.setResourceValues(values)
            let stem = url.deletingPathExtension().lastPathComponent
            let file = folder.appendingPathComponent(stem).appendingPathExtension(animated ? "gif" : highQuality ? "png" : "jpg")
            let thumbnail = folder.appendingPathComponent("thumbnail.jpg")
            var width = image.width, height = image.height
            if animated {
                guard count <= 500, let first = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw CocoaError(.fileReadCorruptFile) }
                width = first.width; height = first.height
                guard let destination = CGImageDestinationCreateWithURL(file as CFURL, UTType.gif.identifier as CFString, count, nil) else {
                    throw CocoaError(.fileWriteUnknown)
                }
                let container = CGImageSourceCopyProperties(source, nil) as? [CFString: Any]
                let gif = container?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
                CGImageDestinationSetProperties(destination, [kCGImagePropertyGIFDictionary: [
                    kCGImagePropertyGIFLoopCount: gif?[kCGImagePropertyGIFLoopCount] ?? 0,
                ]] as CFDictionary)
                for index in 0..<count {
                    guard let frame = CGImageSourceCreateImageAtIndex(source, index, nil) else { throw CocoaError(.fileReadCorruptFile) }
                    let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
                    let timing = properties?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
                    let delay = timing?[kCGImagePropertyGIFUnclampedDelayTime] ?? timing?[kCGImagePropertyGIFDelayTime] ?? 0.1
                    CGImageDestinationAddImage(destination, frame, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay]] as CFDictionary)
                }
                guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
            } else if highQuality {
                let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
                let side = max(properties?[kCGImagePropertyPixelWidth] as? Int ?? 2048, properties?[kCGImagePropertyPixelHeight] as? Int ?? 2048)
                guard let original = downsample(source, maxPixel: side),
                      let destination = CGImageDestinationCreateWithURL(file as CFURL, UTType.png.identifier as CFString, 1, nil) else { throw CocoaError(.fileWriteUnknown) }
                width = original.width; height = original.height
                CGImageDestinationAddImage(destination, original, nil)
                guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
            } else { try write(image, to: file, quality: 0.82) }
            guard let small = downsample(source, maxPixel: 800) else { throw CocoaError(.fileReadCorruptFile) }
            try write(small, to: thumbnail, quality: 0.7)
            let thumb = ThumbnailInfo(height: UInt64(small.height), width: UInt64(small.width), mimetype: "image/jpeg", size: size(thumbnail))
            let mime = animated ? "image/gif" : highQuality ? "image/png" : "image/jpeg"
            let info = ImageInfo(height: UInt64(height), width: UInt64(width), mimetype: mime, size: size(file),
                                 thumbnailInfo: thumb, thumbnailSource: nil, blurhash: nil, isAnimated: animated)
            return MacPreparedImage(folder: folder, file: file, thumbnail: thumbnail, info: info)
        } catch {
            try? FileManager.default.removeItem(at: folder)
            throw error
        }
    }

    private static func downsample(_ source: CGImageSource, maxPixel: Int) -> CGImage? {
        CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary)
    }

    private static func write(_ image: CGImage, to url: URL, quality: Double) throws {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    }

    private static func size(_ url: URL) -> UInt64 {
        UInt64(max(0, (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0))
    }
}
