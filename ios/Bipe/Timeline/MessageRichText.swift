// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Native links keep VoiceOver's link actions. No embedded web content is executed.
struct MessageRichText: View {
    let message: TimelineMessage
    let canOpen: () -> Bool
    @Environment(\.openURL) private var openURL

    var body: some View {
        Text(attributed)
            .foregroundStyle(BipeColor.inkPrimary)
            .tint(BipeColor.accentInk)
            .environment(\.openURL, OpenURLAction { url in
                if canOpen() { openURL(url) }
                return .handled
            })
    }

    private var attributed: AttributedString {
        var result = AttributedString(message.isEmote ? "* \(message.senderName) " : "")
        for run in (message.richText ?? BipeMessageText(body: message.body ?? "")).runs {
            var part = AttributedString(run.text)
            var font = run.code ? Font.system(.body, design: .monospaced) : .body
            if run.bold { font = font.bold() }
            if run.italic { font = font.italic() }
            part.font = font
            if run.strike { part.strikethroughStyle = Text.LineStyle(pattern: .solid) }
            if let link = run.link, let url = URL(string: link) {
                part.link = url
                if BipeProfileLink.matrixUserID(fromPermalink: url) != nil {
                    part.backgroundColor = BipeColor.surfaceSunken
                    part.foregroundColor = BipeColor.accentInk
                }
            }
            result += part
        }
        return result
    }
}
