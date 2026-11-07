// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK

struct MacMessagePayload {
    let body: String
    let richText: BipeMessageText
    var photo: MacPhoto? = nil
    var isEmote = false
    /// Everything the sender wrote that a person can read or copy, for the scam detector.
    var safetyText: String { [body, richText.safetyText, photo?.description].compactMap { $0 }.joined(separator: "\n") }

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
        default: self.init(body: fallback)
        }
    }
}
