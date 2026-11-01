// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-colors, ADR 0213: the chat background behind the timeline. marca draws the blobs and the Bipe marks of
/// BipeChatBackground on chat.background; cor is chat.background alone; liso is surface.base. It stays still while
/// the messages scroll. The chat colours are themed, so it follows the palette; in high contrast every chat colour
/// is the flat chat.background, so marca shows only the colour.
struct ChatBackgroundView: View {
    /// A fixed option for the Cores preview; nil reads the stored choice.
    var option: BipeChatBackground?
    @AppStorage(AppTheme.chatBackgroundKey, store: AppTheme.defaults) private var stored = BipeChatBackground.standard.rawValue

    var body: some View {
        switch option ?? BipeChatBackground(rawValue: stored) ?? .standard {
        case .liso: BipeColor.surfaceBase
        case .cor: BipeColor.chatBackground
        case .marca: composition
        }
    }

    private var composition: some View {
        GeometryReader { box in
            let size = box.size
            let long = max(size.width, size.height), short = min(size.width, size.height)
            ZStack(alignment: .topLeading) {
                BipeColor.chatBackground
                ForEach(BipeChatBackground.blobs.indices, id: \.self) { index in
                    let blob = BipeChatBackground.blobs[index]
                    RadialGradient(colors: [blob.tone.colour.opacity(BipeChatBackground.blobOpacity), blob.tone.colour.opacity(0)],
                                   center: UnitPoint(x: blob.x, y: blob.y), startRadius: 0, endRadius: blob.radius * long)
                }
                // In drawing order: the big mark is last, on top.
                ForEach(BipeChatBackground.marks.indices, id: \.self) { index in
                    let mark = BipeChatBackground.marks[index]
                    let side = mark.side * short
                    BipeMark()
                        .fill(mark.tone.colour.opacity(mark.opacity))
                        .frame(width: side, height: side)
                        .offset(x: mark.x * size.width, y: mark.y * size.height)
                }
            }
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .clipped()
        }
        .accessibilityHidden(true)
    }
}

extension BipeChatBackground {
    /// The option name, bipe_chats_background_<option>.
    var title: String {
        switch self {
        case .marca: BipeStrings.chatsBackgroundMarca
        case .cor: BipeStrings.chatsBackgroundCor
        case .liso: BipeStrings.chatsBackgroundLiso
        }
    }
}
