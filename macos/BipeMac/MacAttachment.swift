// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

enum MacPreparedAttachment: Sendable {
    case image(MacPreparedImage)
    case video(MacPreparedVideo)
    case file(MacPreparedFile)

    var size: UInt64 {
        switch self {
        case .image(let image): image.info.size ?? 0
        case .video(let video): video.info.size ?? 0
        case .file(let file): file.info.size ?? 0
        }
    }

    func remove() {
        switch self {
        case .image(let image): image.remove()
        case .video(let video): video.remove()
        case .file(let file): file.remove()
        }
    }

    func send(on timeline: Timeline, caption: String, reply: String?, description: String) throws -> SendAttachmentJoinHandle {
        let params = parameters(caption: caption, reply: reply, description: description)
        switch self {
        case .image(let image):
            return try timeline.sendImage(params: params, thumbnailSource: .file(filename: image.thumbnail.path), imageInfo: image.info)
        case .video(let video):
            return try timeline.sendVideo(params: params, thumbnailSource: .file(filename: video.thumbnail.path), videoInfo: video.info)
        case .file(let file): return try timeline.sendFile(params: params, fileInfo: file.info)
        }
    }

    func parameters(caption: String, reply: String?, description: String) -> UploadParameters {
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        return UploadParameters(source: .file(filename: fileURL.path), caption: text.isEmpty ? nil : text,
            formattedCaption: nil, mentions: nil, inReplyTo: reply,
            extraContentJson: isImage ? BipeImageDescription.extraContentJson(description) : nil)
    }

    var fileURL: URL {
        switch self {
        case .image(let image): image.file
        case .video(let video): video.file
        case .file(let file): file.file
        }
    }

    private var isImage: Bool { if case .image = self { true } else { false } }
}

/// One attachment lifecycle, including retry and cancellation. A stale preparation cannot replace a new selection.
enum MacAttachmentPhase {
    case idle
    case preparing(UUID)
    case prepared(MacPreparedAttachment)
    case failed(MacPreparedAttachment?, String)
    case sending(MacPreparedAttachment, SendAttachmentJoinHandle)
    case cancelling(MacPreparedAttachment, SendAttachmentJoinHandle)

    var media: MacPreparedAttachment? {
        switch self {
        case .prepared(let media), .sending(let media, _), .cancelling(let media, _): media
        case .failed(let media, _): media
        default: nil
        }
    }
    var preparing: Bool { if case .preparing = self { true } else { false } }
    var sending: Bool {
        switch self { case .sending, .cancelling: true; default: false }
    }
    var error: String? { if case .failed(_, let error) = self { error } else { nil } }
    func matches(_ revision: UUID) -> Bool { if case .preparing(let value) = self { value == revision } else { false } }
}
