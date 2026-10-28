// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import ImageIO
import MatrixRustSDK
import UIKit

/// Media of chat messages through the SDK media cache. Encrypted media: the event's MediaSource
/// holds the key and the SDK decrypts on the phone (spec timeline-media notes). Decrypted files
/// stay in the app cache.
extension MediaLoader {
    private static let images = NSCache<NSString, UIImage>()
    private static let files = MediaFileHandles()

    /// The row image: the event thumbnail when there is one, else the media itself, at most maxPixel on the long side.
    /// A sticker always loads the media itself: an animated WebP plays only from the file (ADR 0205).
    func previewImage(_ media: MediaMessage, maxPixel: Int) async -> UIImage? {
        let thumbnailSource = media.kind == .sticker ? nil : media.thumbnailSource
        let key = "\(thumbnailSource?.url ?? media.source.url)#\(maxPixel)" as NSString
        if let cached = Self.images.object(forKey: key) {
            return cached
        }
        let data: Data?
        if let thumbnail = thumbnailSource {
            data = try? await client.getMediaContent(mediaSource: thumbnail.source)
        } else if media.kind == .image || media.kind == .sticker {
            data = try? await client.getMediaContent(mediaSource: media.source.source)
        } else {
            data = nil
        }
        guard let data else { return nil }
        DataUseMeter.shared.recordReceived(Int64(data.count), url: thumbnailSource?.url ?? media.source.url)
        guard let image = Self.decode(data, maxPixel: maxPixel, animated: media.isAnimated || media.mayBeAnimated) else { return nil }
        Self.images.setObject(image, forKey: key)
        return image
    }

    /// A pack sticker for the picker, from its file: an animated WebP comes back with its frames (ADR 0205).
    func stickerImage(mxcUrl: String, maxPixel: Int) async -> UIImage? {
        let key = "\(mxcUrl)#sticker#\(maxPixel)" as NSString
        if let cached = Self.images.object(forKey: key) {
            return cached
        }
        guard let source = try? MediaSource.fromUrl(url: mxcUrl),
              let data = try? await client.getMediaContent(mediaSource: source) else { return nil }
        DataUseMeter.shared.recordReceived(Int64(data.count), url: mxcUrl)
        guard let image = Self.decode(data, maxPixel: maxPixel, animated: true) else { return nil }
        Self.images.setObject(image, forKey: key)
        return image
    }

    /// The full image for media-viewer.
    func fullImage(_ media: MediaMessage) async -> UIImage? {
        let key = "\(media.source.url)#full" as NSString
        if let cached = Self.images.object(forKey: key) {
            return cached
        }
        guard let data = try? await client.getMediaContent(mediaSource: media.source.source) else { return nil }
        DataUseMeter.shared.recordReceived(Int64(data.count), url: media.source.url)
        guard let image = Self.decode(data, maxPixel: 4096, animated: media.isAnimated) else { return nil }
        Self.images.setObject(image, forKey: key)
        return image
    }

    /// Downloads the media to a file in the cache (for video, voice, files, share and save).
    /// The file lives while the app runs.
    func file(_ ref: MediaRef, filename: String, mimeType: String?) async throws -> URL {
        if let url = await Self.files.url(for: ref.url) {
            return url
        }
        let handle = try await client.getMediaFile(mediaSource: ref.source,
                                                   filename: filename,
                                                   mimeType: mimeType ?? "application/octet-stream",
                                                   useCache: true,
                                                   tempDir: nil)
        let url = URL(fileURLWithPath: try handle.path())
        DataUseMeter.shared.recordReceived(Int64(MediaPreparation.fileSize(url)), url: ref.url)
        await Self.files.keep(handle, url: url, for: ref.url)
        return url
    }

    private static func decode(_ data: Data, maxPixel: Int, animated: Bool) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        let count = CGImageSourceGetCount(source)
        if animated, count > 1 {
            var frames: [UIImage] = []
            var duration = 0.0
            for index in 0..<min(count, 300) {
                guard let frame = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else { continue }
                frames.append(UIImage(cgImage: frame))
                duration += frameDuration(source, index)
            }
            return UIImage.animatedImage(with: frames, duration: duration)
        }
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }

    private static func frameDuration(_ source: CGImageSource, _ index: Int) -> Double {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any] ?? [:]
        let containers = [kCGImagePropertyGIFDictionary, kCGImagePropertyPNGDictionary, kCGImagePropertyWebPDictionary]
        for container in containers {
            if let values = properties[container] as? [CFString: Any] {
                let delay = values[kCGImagePropertyGIFUnclampedDelayTime] as? Double
                    ?? values[kCGImagePropertyAPNGUnclampedDelayTime] as? Double
                    ?? values[kCGImagePropertyWebPUnclampedDelayTime] as? Double
                    ?? values[kCGImagePropertyGIFDelayTime] as? Double
                    ?? values[kCGImagePropertyAPNGDelayTime] as? Double
                    ?? values[kCGImagePropertyWebPDelayTime] as? Double
                if let delay, delay > 0.01 {
                    return delay
                }
            }
        }
        return 0.1
    }
}

/// Keeps the SDK file handles alive: the SDK deletes the temporary file when its handle goes away.
private actor MediaFileHandles {
    private var handles: [String: (handle: MediaFileHandle, url: URL)] = [:]

    func url(for key: String) -> URL? {
        guard let entry = handles[key], FileManager.default.fileExists(atPath: entry.url.path) else { return nil }
        return entry.url
    }

    func keep(_ handle: MediaFileHandle, url: URL, for key: String) {
        handles[key] = (handle, url)
    }
}
