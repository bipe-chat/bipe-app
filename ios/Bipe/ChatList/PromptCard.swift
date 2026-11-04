// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec chat-list-prompt (bipe-chat/bipe#894): the one card shape of every chat list prompt. Title, body, an optional
/// extra line, one filled button and text buttons (Agora não last, or the close label of the remote banner).
struct PromptCard: View {
    struct Action {
        let title: String
        let run: @MainActor () -> Void
    }

    let title: String
    var body_: String?
    var note: String?
    var primary: Action?
    var texts: [Action]

    init(title: String, body: String?, note: String? = nil, primary: Action?, texts: [Action]) {
        self.title = title
        body_ = body
        self.note = note
        self.primary = primary
        self.texts = texts
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: title)
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            ForEach([body_, note].compactMap { $0 }, id: \.self) { line in
                Text(verbatim: line)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if primary != nil || !texts.isEmpty {
                HStack(spacing: 16) {
                    if let primary {
                        Button(action: primary.run) {
                            Text(verbatim: primary.title)
                                .font(.subheadline.weight(.semibold))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .foregroundStyle(BipeColor.inkOnAccent)
                                .background(Capsule().fill(BipeColor.accentFill))
                        }
                    }
                    ForEach(texts.indices, id: \.self) { index in
                        Button(action: texts[index].run) {
                            Text(verbatim: texts[index].title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(BipeColor.accentInk)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
    }
}
