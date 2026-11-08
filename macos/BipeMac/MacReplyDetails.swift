// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

enum MacReplyPreview {
    static func text(_ content: TimelineItemContent, isOwn: Bool) -> String {
        guard case .msgLike(let message) = content else { return "" }
        switch message.kind {
        case .message(let value):
            switch value.msgType {
            case .image(let image): return caption(image.caption, fallback: BipeStrings.appMediaImage)
            case .video(let video): return caption(video.caption, fallback: BipeStrings.appMediaVideo)
            case .file(let file): return caption(file.caption, fallback: BipeStrings.appMediaFile)
            case .audio(let audio): return audio.voice != nil ? BipeStrings.appMediaVoice : audio.filename
            case .location: return BipeStrings.appLocationTitle
            default: return MacMessagePayload(type: value.msgType, fallback: value.body, eventJson: nil).richText.text
            }
        case .redacted: return isOwn ? BipeStrings.appMessageDeletedOwn : BipeStrings.appMessageDeleted
        case .unableToDecrypt: return BipeStrings.appTimelineUndecryptable
        case .sticker: return BipeStrings.appMediaSticker
        case .liveLocation: return BipeStrings.appLocationLiveTitle
        default: return ""
        }
    }

    static func safetyText(_ content: TimelineItemContent) -> String {
        guard case .msgLike(let message) = content,
              case .message(let value) = message.kind else { return "" }
        let payload = MacMessagePayload(type: value.msgType, fallback: value.body, eventJson: nil)
        let caption: MacMessagePayload?
        switch value.msgType {
        case .video(let video): caption = MacMessagePayload(body: video.caption ?? "", formatted: video.formattedCaption)
        case .file(let file): caption = MacMessagePayload(body: file.caption ?? "", formatted: file.formattedCaption)
        default: caption = nil
        }
        return [payload.safetyText, caption?.safetyText].compactMap { $0 }.joined(separator: "\n")
    }

    private static func caption(_ value: String?, fallback: String) -> String {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return fallback }
        return value
    }
}

/// Spec message-reply: a missing original can consume at most five history pages.
@MainActor
enum MacOriginalLookup {
    static func find(loaded: () -> String?, paginate: () async throws -> Bool) async throws -> String? {
        for page in 0...5 {
            try Task.checkCancellation()
            if let row = loaded() { return row }
            guard page < 5 else { return nil }
            if try await paginate() { return loaded() }
        }
        return nil
    }
}
