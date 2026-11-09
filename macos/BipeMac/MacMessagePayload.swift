// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK

struct MacMessagePayload {
    let body: String
    let richText: BipeMessageText
    var photo: MacPhoto? = nil
    var file: MacFile? = nil
    var video: MacVideo? = nil
    var isEmote = false
    var isEdited = false
    var isDeleted = false
    var isUndecryptable = false
    /// Everything the sender wrote that a person can read or copy, for the scam detector.
    var safetyText: String { [body, richText.safetyText, photo?.description, file?.content.filename, file?.name.filename, video?.content.filename].compactMap { $0 }.joined(separator: "\n") }

    init(body: String, formatted: FormattedBody? = nil) {
        self.body = body
        richText = BipeMessageText(body: body, html: formatted?.format == .html ? formatted?.body : nil)
    }

    init(type: MessageType, fallback: String, eventJson: String?) {
        switch type {
        case .text(let content): self.init(body: content.body, formatted: content.formatted)
        case .notice(let content): self.init(body: content.body, formatted: content.formatted)
        case .emote(let content):
            self.init(body: content.body, formatted: content.formatted)
            isEmote = true
        case .image(let content):
            self.init(body: content.caption ?? "", formatted: content.formattedCaption)
            photo = MacPhoto(content: content, description: BipeImageDescription.read(eventJson: eventJson))
        case .video(let content):
            self.init(body: content.caption ?? "", formatted: content.formattedCaption)
            video = MacVideo(content: content)
        case .file(let content):
            self.init(body: content.caption ?? "", formatted: content.formattedCaption)
            file = MacFile(content: content)
        default: self.init(body: fallback)
        }
    }

    init?(kind: MsgLikeKind, eventJson: String?) {
        switch kind {
        case .message(let message):
            self.init(type: message.msgType, fallback: message.body, eventJson: eventJson)
            isEdited = message.isEdited
        case .redacted:
            self.init(body: "")
            isDeleted = true
        case .unableToDecrypt:
            self.init(body: BipeStrings.webTimelineUndecryptable)
            isUndecryptable = true
        default: return nil
        }
    }

    func warning(including reply: MessageReply?, isOwn: Bool, eventID: String) -> ScamWarningKind? {
        guard !isOwn, !isDeleted else { return nil }
        return ScamDetector.warningKind(for: safetyText + "\n" + (reply?.safetyText ?? ""), eventID: eventID)
    }
}
