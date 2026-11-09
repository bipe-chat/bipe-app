// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Foundation
import MatrixRustSDK
import UniformTypeIdentifiers

struct MacVideo {
    let content: VideoMessageContent
    var file: MacFile {
        MacFile(content: FileMessageContent(filename: content.filename, caption: content.caption,
            formattedCaption: content.formattedCaption, source: content.source,
            info: FileInfo(mimetype: content.info?.mimetype, size: content.info?.size, thumbnailInfo: nil, thumbnailSource: nil)))
    }
    var durationLabel: String? {
        guard let duration = content.info?.duration, duration.isFinite, duration >= 0, duration < 86_400 * 365 else { return nil }
        let format = DateComponentsFormatter()
        format.allowedUnits = [.hour, .minute, .second]
        format.unitsStyle = .positional
        format.zeroFormattingBehavior = .pad
        return format.string(from: duration)
    }
    var previewSize: CGSize {
        let ratio = CGFloat(content.info?.width ?? 320) / max(1, CGFloat(content.info?.height ?? 240))
        let width = min(260, 300 * max(0.2, min(5, ratio)))
        return CGSize(width: width, height: min(300, width / max(0.2, min(5, ratio))))
    }
}

final class MacPreparedVideo: Identifiable, Sendable {
    let id = UUID()
    let folder: URL
    let file: URL
    let thumbnail: URL
    let info: VideoInfo
    private let original: MacPreparedFile

    init(original: MacPreparedFile, video: VideoPreparation.Prepared) {
        self.original = original; folder = original.folder
        file = video.file; thumbnail = video.thumbnail; info = video.info
    }
    func remove() { try? FileManager.default.removeItem(at: folder) }
    deinit { remove() }

    static func prepare(_ source: URL, highQuality: Bool, maxUploadSize: UInt64?, under root: URL = MacTransferFiles.root) async throws -> MacPreparedVideo {
        // A private copy keeps security-scoped picker/provider URLs alive through export and SDK upload.
        let copy = try MacTransferFiles.prepare(source, maxUploadSize: nil, under: root)
        do {
            let video = try await VideoPreparation.prepare(copy.file, highQuality: highQuality, folder: copy.folder)
            try MacTransferFiles.checkSize(video.info.size ?? 0, limit: maxUploadSize)
            try Task.checkCancellation()
            return MacPreparedVideo(original: copy, video: video)
        } catch { copy.remove(); throw error }
    }
}

enum MacAttachmentKind: Sendable, Equatable {
    case image, video, file
    static func of(_ url: URL) -> Self {
        guard let type = UTType(filenameExtension: url.pathExtension) else { return .file }
        if type.conforms(to: .image) { return .image }
        if type.conforms(to: .movie) || type.conforms(to: .video) { return .video }
        return .file
    }
}
