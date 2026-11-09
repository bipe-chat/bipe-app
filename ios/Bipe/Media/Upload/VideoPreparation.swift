// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import Foundation
import ImageIO
import MatrixRustSDK
import UniformTypeIdentifiers

/// The upload-preview video contract, compiled by iOS and Mac. The caller owns the working folder.
enum VideoPreparation {
    struct Prepared {
        let file: URL
        let thumbnail: URL
        let info: VideoInfo
    }

    static func prepare(_ source: URL, highQuality: Bool, folder: URL) async throws -> Prepared {
        try Task.checkCancellation()
        let work = folder.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var completed = false
        defer { if !completed { try? FileManager.default.removeItem(at: work) } }
        var output = source
        if !highQuality {
            let target = work.appendingPathComponent(source.deletingPathExtension().lastPathComponent).appendingPathExtension("mp4")
            guard let export = AVAssetExportSession(asset: AVURLAsset(url: source), presetName: AVAssetExportPreset1280x720) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            export.shouldOptimizeForNetworkUse = true
            export.metadata = []
            export.metadataItemFilter = .forSharing()
            // A failed conversion is an error. Never silently upload the unprocessed original.
            try await withTaskCancellationHandler {
                try await export.export(to: target, as: .mp4)
            } onCancel: { export.cancelExport() }
            output = target
        }
        try Task.checkCancellation()
        let asset = AVURLAsset(url: output)
        guard let track = try await asset.loadTracks(withMediaType: .video).first,
              try await asset.load(.isPlayable) else { throw CocoaError(.fileReadCorruptFile) }
        let duration = try await asset.load(.duration).seconds
        let natural = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let size = natural.applying(transform)
        guard size.width.isFinite, size.height.isFinite, abs(size.width) > 0, abs(size.height) > 0, abs(size.width) <= 1_000_000, abs(size.height) <= 1_000_000 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 800, height: 800)
        let frame = try await generator.image(at: .zero).image
        try Task.checkCancellation()
        let thumbnail = work.appendingPathComponent("video-thumbnail.jpg")
        guard let destination = CGImageDestinationCreateWithURL(thumbnail as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
            throw CocoaError(.fileWriteUnknown)
        }
        CGImageDestinationAddImage(destination, frame, [kCGImageDestinationLossyCompressionQuality: 0.7] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw CocoaError(.fileWriteUnknown) }
        let info = VideoInfo(duration: duration.isFinite ? duration : nil,
                             height: UInt64(abs(size.height).rounded()), width: UInt64(abs(size.width).rounded()),
                             mimetype: UTType(filenameExtension: output.pathExtension)?.preferredMIMEType ?? "video/mp4",
                             size: fileSize(output),
                             thumbnailInfo: ThumbnailInfo(height: UInt64(frame.height), width: UInt64(frame.width),
                                                          mimetype: "image/jpeg", size: fileSize(thumbnail)),
                             thumbnailSource: nil, blurhash: Blurhash.encode(frame))
        completed = true
        return Prepared(file: output, thumbnail: thumbnail, info: info)
    }

    private static func fileSize(_ url: URL) -> UInt64 {
        UInt64(max(0, (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0))
    }
}
