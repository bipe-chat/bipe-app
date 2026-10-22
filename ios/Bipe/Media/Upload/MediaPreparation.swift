// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Foundation
import ImageIO
import MatrixRustSDK
import UniformTypeIdentifiers

/// The setting bipe_chats_media_quality (state quality of spec upload-preview). Stored under the
/// settings row id chats.mediaQuality.
enum MediaQuality: String, CaseIterable {
    case standard
    case high

    static var current: MediaQuality {
        UserDefaults.standard.string(forKey: BipeSettingsRow.chatsMediaQuality.rawValue).flatMap(MediaQuality.init) ?? .standard
    }
}

/// A picked file made ready for Timeline.sendImage, sendVideo or sendFile (spec upload-preview notes):
/// photos at most 2048 px and videos at most 1280 px in standard quality, a thumbnail and a blurhash
/// for every image and video, and no location or camera data in photos.
struct PreparedMedia {
    enum Info {
        case image(ImageInfo)
        case video(VideoInfo)
        case file(FileInfo)
    }

    var file: URL
    var filename: String
    var size: UInt64
    var info: Info
    var thumbnail: URL?
}

enum MediaPreparation {
    static let maxPhotoSide = 2048
    static let maxVideoSide: CGFloat = 1280
    static let thumbnailSide = 800

    static func prepare(_ source: URL, kind: PickedMedia.Kind, quality: MediaQuality) async throws -> PreparedMedia {
        let folder = try workFolder()
        switch kind {
        case .image:
            return try prepareImage(source, quality: quality, folder: folder)
        case .video:
            return try await prepareVideo(source, quality: quality, folder: folder)
        case .file:
            return PreparedMedia(file: source,
                                 filename: source.lastPathComponent,
                                 size: fileSize(source),
                                 info: .file(FileInfo(mimetype: mimeType(source), size: fileSize(source), thumbnailInfo: nil, thumbnailSource: nil)),
                                 thumbnail: nil)
        }
    }

    static func fileSize(_ url: URL) -> UInt64 {
        UInt64(max(0, (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0))
    }

    static func mimeType(_ url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }

    // MARK: Images

    private static func prepareImage(_ source: URL, quality: MediaQuality, folder: URL) throws -> PreparedMedia {
        guard let imageSource = CGImageSourceCreateWithURL(source as CFURL, nil),
              let type = CGImageSourceGetType(imageSource) else { throw CocoaError(.fileReadCorruptFile) }
        let isAnimated = CGImageSourceGetCount(imageSource) > 1
        let name = source.deletingPathExtension().lastPathComponent
        let output: URL
        if quality == .high || isAnimated {
            // As it is, without the metadata (location, camera).
            let ext = UTType(type as String)?.preferredFilenameExtension ?? source.pathExtension
            output = folder.appendingPathComponent(name).appendingPathExtension(ext)
            guard let destination = CGImageDestinationCreateWithURL(output as CFURL, type, CGImageSourceGetCount(imageSource), nil) else {
                throw CocoaError(.fileWriteUnknown)
            }
            let options: [CFString: Any] = [
                kCGImageDestinationMetadata: CGImageMetadataCreateMutable(),
                kCGImageMetadataShouldExcludeGPS: true,
                kCGImageMetadataShouldExcludeXMP: true,
            ]
            var error: Unmanaged<CFError>?
            if !CGImageDestinationCopyImageSource(destination, imageSource, options as CFDictionary, &error) {
                // Formats that cannot copy: re-encode each frame without properties.
                guard let fallback = CGImageDestinationCreateWithURL(output as CFURL, type, CGImageSourceGetCount(imageSource), nil) else {
                    throw CocoaError(.fileWriteUnknown)
                }
                for index in 0..<CGImageSourceGetCount(imageSource) {
                    if let frame = CGImageSourceCreateImageAtIndex(imageSource, index, nil) {
                        CGImageDestinationAddImage(fallback, frame, nil)
                    }
                }
                guard CGImageDestinationFinalize(fallback) else { throw CocoaError(.fileWriteUnknown) }
            }
        } else {
            // Standard: at most 2048 px, JPEG. Drawing the image drops every property.
            guard let image = downscaled(imageSource, maxPixel: maxPhotoSide) else { throw CocoaError(.fileReadCorruptFile) }
            output = folder.appendingPathComponent(name).appendingPathExtension("jpg")
            try writeJPEG(image, to: output, quality: 0.82)
        }
        guard let written = CGImageSourceCreateWithURL(output as CFURL, nil),
              let full = CGImageSourceCreateImageAtIndex(written, 0, [kCGImageSourceShouldCache: false] as CFDictionary) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let (width, height) = orientedSize(written, image: full)
        let thumbnail = try makeThumbnail(written, folder: folder, name: name)
        let size = fileSize(output)
        let info = ImageInfo(height: UInt64(height),
                             width: UInt64(width),
                             mimetype: mimeType(output),
                             size: size,
                             thumbnailInfo: thumbnail?.info,
                             thumbnailSource: nil,
                             blurhash: thumbnail?.blurhash,
                             isAnimated: isAnimated)
        return PreparedMedia(file: output, filename: output.lastPathComponent, size: size, info: .image(info), thumbnail: thumbnail?.url)
    }

    private static func orientedSize(_ source: CGImageSource, image: CGImage) -> (Int, Int) {
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let orientation = properties?[kCGImagePropertyOrientation] as? UInt32 ?? 1
        return orientation >= 5 ? (image.height, image.width) : (image.width, image.height)
    }

    private static func downscaled(_ source: CGImageSource, maxPixel: Int) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    private static func writeJPEG(_ image: CGImage, to url: URL, quality: Double) throws {
        guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
    }

    private static func makeThumbnail(_ source: CGImageSource, folder: URL, name: String) throws -> (url: URL, info: ThumbnailInfo, blurhash: String?)? {
        guard let image = downscaled(source, maxPixel: thumbnailSide) else { return nil }
        return try thumbnail(image, folder: folder, name: name)
    }

    private static func thumbnail(_ image: CGImage, folder: URL, name: String) throws -> (url: URL, info: ThumbnailInfo, blurhash: String?) {
        let url = folder.appendingPathComponent("\(name)-thumbnail").appendingPathExtension("jpg")
        try writeJPEG(image, to: url, quality: 0.7)
        let info = ThumbnailInfo(height: UInt64(image.height), width: UInt64(image.width), mimetype: "image/jpeg", size: fileSize(url))
        return (url, info, Blurhash.encode(image))
    }

    // MARK: Videos

    private static func prepareVideo(_ source: URL, quality: MediaQuality, folder: URL) async throws -> PreparedMedia {
        let name = source.deletingPathExtension().lastPathComponent
        var output = source
        if quality == .standard {
            let asset = AVURLAsset(url: source)
            let target = folder.appendingPathComponent(name).appendingPathExtension("mp4")
            try? FileManager.default.removeItem(at: target)
            if let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) {
                export.outputURL = target
                export.outputFileType = .mp4
                export.shouldOptimizeForNetworkUse = true
                // No location or other metadata in the sent file.
                export.metadata = []
                export.metadataItemFilter = .forSharing()
                await export.export()
                if export.status == .completed {
                    output = target
                }
            }
        }
        let asset = AVURLAsset(url: output)
        let duration = try await asset.load(.duration).seconds
        var width = 0, height = 0
        if let track = try await asset.loadTracks(withMediaType: .video).first {
            let natural = try await track.load(.naturalSize)
            let transform = try await track.load(.preferredTransform)
            let size = natural.applying(transform)
            width = Int(abs(size.width))
            height = Int(abs(size.height))
        }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: thumbnailSide, height: thumbnailSide)
        var thumbnail: (url: URL, info: ThumbnailInfo, blurhash: String?)?
        if let frame = try? await generator.image(at: .zero).image {
            thumbnail = try self.thumbnail(frame, folder: folder, name: name)
        }
        let size = fileSize(output)
        let info = VideoInfo(duration: duration.isFinite ? duration : nil,
                             height: UInt64(height),
                             width: UInt64(width),
                             mimetype: mimeType(output),
                             size: size,
                             thumbnailInfo: thumbnail?.info,
                             thumbnailSource: nil,
                             blurhash: thumbnail?.blurhash)
        return PreparedMedia(file: output, filename: output.lastPathComponent, size: size, info: .video(info), thumbnail: thumbnail?.url)
    }

    // MARK: Files

    /// A folder in the app cache for picked and prepared files. Upload sources must stay until the send queue has them.
    static func workFolder() throws -> URL {
        let folder = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Uploads", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}
