// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// A media source of an event. The SDK type is a class; rows compare it by its URL.
struct MediaRef: Equatable {
    let source: MediaSource

    var url: String { source.url() }

    static func == (lhs: MediaRef, rhs: MediaRef) -> Bool {
        lhs.url == rhs.url
    }
}

/// Type MediaMessage of spec timeline-media.
struct MediaMessage: Equatable {
    enum Kind: Equatable {
        case image
        case video
        case file
        case sticker
    }

    var kind: Kind
    var filename: String
    var caption: String?
    var mimeType: String?
    var sizeLabel: String?
    var width: Int?
    var height: Int?
    var durationLabel: String?
    var blurhash: String?
    /// Own media while sending: percent 0 to 100.
    var uploadProgress: Int?
    var isAnimated = false
    var source: MediaRef
    var thumbnailSource: MediaRef?
    /// image only: the sender's description for screen readers (bipe-chat/bipe#501, registries/image-descriptions.json).
    var description: String?
    /// sticker only: the room pack of stickerPackField (message-actions viewStickerPack, ADR 0205).
    var stickerPack: StickerPackRef?

    /// Width over height for the row box. Without size: 4:3 (spec timeline-media notes).
    var aspectRatio: CGFloat {
        guard let width, let height, width > 0, height > 0 else { return 4.0 / 3.0 }
        return CGFloat(width) / CGFloat(height)
    }

    /// The accessibility label and preview text (spec timeline-media notes).
    var kindLabel: String {
        switch kind {
        case .image: BipeStrings.appMediaImage
        case .video: BipeStrings.appMediaVideo
        case .file: BipeStrings.appMediaFile
        case .sticker: BipeStrings.appMediaSticker
        }
    }

    /// What VoiceOver reads: the description of an image before the caption (spec timeline-media notes).
    var accessibilityLabel: String {
        let base = kind == .image ? description.map(BipeStrings.appMediaImageDescribed) ?? kindLabel : kindLabel
        return caption?.nilIfBlank.map { "\(base), \($0)" } ?? base
    }

    /// image, video and sticker open media-viewer; a file opens the system preview.
    var opensInViewer: Bool {
        kind != .file
    }
}

/// Type VoiceMessage of spec voice-message. Playback state lives in VoicePlayer.
struct VoiceMessage: Equatable {
    var isVoice: Bool
    var filename: String
    var duration: TimeInterval
    /// One bar per entry, 0 to 1024. Empty draws a flat line.
    var waveform: [UInt16]
    /// The caption of a voice message: the sender's transcript (ADR 0023).
    var transcript: String?
    var mimeType: String?
    var source: MediaRef

    var durationLabel: String {
        MediaFormat.duration(duration)
    }
}

/// What a message event holds, for the media rows.
enum MediaContent: Equatable {
    case media(MediaMessage)
    case voice(VoiceMessage)

    init?(_ kind: MsgLikeKind) {
        switch kind {
        case .message(let content):
            self.init(content.msgType)
        case .sticker(let body, let info, let source):
            var media = MediaMessage(image: info, filename: body, caption: nil, source: source)
            media.kind = .sticker
            self = .media(media)
        default:
            return nil
        }
    }

    init?(_ type: MessageType) {
        switch type {
        case .image(let content):
            var media = MediaMessage(image: content.info, filename: content.filename, caption: content.caption, source: content.source)
            media.kind = .image
            self = .media(media)
        case .video(let content):
            let info = content.info
            self = .media(MediaMessage(kind: .video,
                                       filename: content.filename,
                                       caption: content.caption,
                                       mimeType: info?.mimetype,
                                       sizeLabel: info?.size.map(MediaFormat.size),
                                       width: info?.width.map { Int($0) },
                                       height: info?.height.map { Int($0) },
                                       durationLabel: info?.duration.map(MediaFormat.duration),
                                       blurhash: info?.blurhash,
                                       source: MediaRef(source: content.source),
                                       thumbnailSource: info?.thumbnailSource.map(MediaRef.init)))
        case .file(let content):
            let info = content.info
            self = .media(MediaMessage(kind: .file,
                                       filename: content.filename,
                                       caption: content.caption,
                                       mimeType: info?.mimetype,
                                       sizeLabel: info?.size.map(MediaFormat.size),
                                       source: MediaRef(source: content.source),
                                       thumbnailSource: info?.thumbnailSource.map(MediaRef.init)))
        case .audio(let content):
            // The voice marker makes a voice message; its caption is the transcript (ADR 0023).
            let isVoice = content.voice != nil
            self = .voice(VoiceMessage(isVoice: isVoice,
                                       filename: content.filename,
                                       duration: content.audio?.duration ?? content.info?.duration ?? 0,
                                       waveform: content.audio?.waveform ?? [],
                                       transcript: isVoice ? content.caption?.nilIfBlank : nil,
                                       mimeType: content.info?.mimetype,
                                       source: MediaRef(source: content.source)))
        default:
            return nil
        }
    }
}

extension MediaMessage {
    init(image info: ImageInfo?, filename: String, caption: String?, source: MediaSource) {
        self.init(kind: .image,
                  filename: filename,
                  caption: caption,
                  mimeType: info?.mimetype,
                  sizeLabel: info?.size.map(MediaFormat.size),
                  width: info?.width.map { Int($0) },
                  height: info?.height.map { Int($0) },
                  blurhash: info?.blurhash,
                  isAnimated: info?.isAnimated ?? false,
                  source: MediaRef(source: source),
                  thumbnailSource: info?.thumbnailSource.map(MediaRef.init))
    }
}

enum MediaFormat {
    /// File size as the system writes it ("2,4 MB").
    static func size(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(clamping: bytes), countStyle: .file)
    }

    /// "0:42", "12:05".
    static func duration(_ seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    /// Upload percent of an own media message while it sends.
    static func uploadPercent(_ state: EventSendState?) -> Int? {
        guard case .some(.notSentYet(let progress)) = state else { return nil }
        guard let progress, progress.progress.total > 0 else { return 0 }
        return Int(min(100, progress.progress.current * 100 / progress.progress.total))
    }
}

extension String {
    var nilIfBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
