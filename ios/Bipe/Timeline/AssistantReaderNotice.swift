// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-timeline notes, assistant readers (ADR 0130, bipe-chat/bipe#439): a persistent notice at the top of the
/// timeline for a member whose assistant reads the chat. It has no action and no close.
struct AssistantReaderNotice: View {
    let name: String

    var body: some View {
        HStack(spacing: 8) {
            PhosphorIcon.info.image
                .resizable()
                .frame(width: 16, height: 16)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(BipeStrings.appAssistantReads(name))
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(BipeColor.surfaceRaised)
        .accessibilityElement(children: .combine)
    }
}
