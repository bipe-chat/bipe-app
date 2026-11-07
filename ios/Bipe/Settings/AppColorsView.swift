// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// Spec app-colors: the user picks a palette and a chat background. The palette sets the accent colours, the own
/// bubble, the chat background and the app icon at once.
struct AppColorsView: View {
    @State private var selected = AppTheme.stored
    @State private var selectedBackground = AppTheme.storedChatBackground

    var body: some View {
        List {
            Section {
                ForEach(BipePalette.allCases, id: \.self) { palette in
                    Button {
                        pick(palette)
                    } label: {
                        HStack(spacing: 16) {
                            AppIconPreview(palette: palette)
                            Text(palette.title)
                                .foregroundStyle(BipeColor.inkPrimary)
                            Spacer()
                            if selected == palette {
                                PhosphorIcon.check.image
                                    .resizable()
                                    .frame(width: 18, height: 18)
                                    .foregroundStyle(BipeColor.accentInk)
                                    .accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected == palette ? .isSelected : [])
                }
            } footer: {
                Text(BipeStrings.chatsColorsFooter)
            }
            // ADR 0213: the chat background, in the palette just picked.
            Section {
                ForEach(BipeChatBackground.allCases, id: \.self) { background in
                    Button {
                        pickBackground(background)
                    } label: {
                        HStack(spacing: 16) {
                            ChatBackgroundPreview(background: background)
                            Text(background.title)
                                .foregroundStyle(BipeColor.inkPrimary)
                            Spacer()
                            if selectedBackground == background {
                                PhosphorIcon.check.image
                                    .resizable()
                                    .frame(width: 18, height: 18)
                                    .foregroundStyle(BipeColor.accentInk)
                                    .accessibilityHidden(true)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selectedBackground == background ? .isSelected : [])
                }
            } header: {
                Text(BipeStrings.chatsBackground)
            } footer: {
                Text(BipeStrings.chatsBackgroundFooter)
            }
        }
        .navigationTitle(BipeStrings.chatsColors)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func pickBackground(_ background: BipeChatBackground) {
        guard background != selectedBackground else { return }
        selectedBackground = background
        AppTheme.save(background)
        SyncedSettings.changed(BipeSyncedSettings.chatBackground)
        BipeHaptics.play(.tick)
    }

    private func pick(_ palette: BipePalette) {
        guard palette != selected else { return }
        selected = palette
        AppTheme.save(palette)
        SyncedSettings.changed(BipeSyncedSettings.palette)
        BipeHaptics.play(.tick)
        // iOS shows its own alert after the change. A failed change keeps the theme and the old icon (spec notes).
        guard UIApplication.shared.supportsAlternateIcons,
              UIApplication.shared.alternateIconName != palette.alternateIconName else { return }
        UIApplication.shared.setAlternateIconName(palette.alternateIconName) { _ in }
    }
}

/// The app icon of a palette: the mark on its icon background, the same picture as the home screen icon.
struct AppIconPreview: View {
    let palette: BipePalette
    @ScaledMetric(relativeTo: .body) private var side: CGFloat = 44

    var body: some View {
        RoundedRectangle(cornerRadius: side * 0.225, style: .continuous)
            .fill(Color(rgb: palette.iconBackground))
            .overlay {
                // The mark is 33 of the icon's 64 units (bipe-shared design/tokens.json appThemes.icon).
                BipeMark()
                    .fill(Color(rgb: BipePalette.iconMark))
                    .padding(side * 31 / 128)
            }
            .frame(width: side, height: side)
            .accessibilityHidden(true)
    }
}

/// A chat background option as a small portrait card, the same drawing as the chat.
struct ChatBackgroundPreview: View {
    let background: BipeChatBackground
    @ScaledMetric(relativeTo: .body) private var height: CGFloat = 44

    var body: some View {
        ChatBackgroundView(option: background)
            .frame(width: height * 0.7, height: height)
            .clipShape(RoundedRectangle(cornerRadius: height * 0.18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: height * 0.18, style: .continuous).stroke(BipeColor.lineSubtle))
            .accessibilityHidden(true)
    }
}

private extension Color {
    /// A generated 0xRRGGBB value (BipePalette), not a literal.
    init(rgb: UInt32) {
        self.init(red: Double((rgb >> 16) & 0xFF) / 255, green: Double((rgb >> 8) & 0xFF) / 255, blue: Double(rgb & 0xFF) / 255)
    }
}
