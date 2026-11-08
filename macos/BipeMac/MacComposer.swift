// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The phone's composer chrome, with Mac picker and keyboard adapters. Voice remains in bipe#1220.
struct MacComposer<Field: View>: View {
    let isEditing: Bool
    let canSend: Bool
    let canAttach: Bool
    let preparing: Bool
    let choose: (AttachmentChoice) -> Void
    let send: () -> Void
    @ViewBuilder let field: () -> Field

    var body: some View {
        BipeComposerRow {
            if !isEditing {
                AttachmentMenu(canPoll: false, supported: [.photos, .file], choose: choose) {
                    BipeComposerControlIcon(glyph: .plus, surface: false)
                }
                .disabled(!canAttach)
                .accessibilityLabel(BipeStrings.appComposerAttach)
                .accessibilityValue(preparing ? BipeStrings.appUploadPreparing : "")
                .help(BipeStrings.appComposerAttach)
                .overlay { if preparing { ProgressView().accessibilityLabel(BipeStrings.appUploadPreparing) } }
            }
            BipeComposerInput(content: field).frame(maxWidth: .infinity)
            Button(action: send) {
                BipeComposerControlIcon(glyph: isEditing ? .check : .send, enabled: canSend, accented: canSend)
            }
            .buttonStyle(.plain).disabled(!canSend)
            .keyboardShortcut(.return, modifiers: .command)
            .accessibilityLabel(isEditing ? BipeStrings.appMessageSaveEdit : BipeStrings.appTimelineSend)
        }
    }
}
