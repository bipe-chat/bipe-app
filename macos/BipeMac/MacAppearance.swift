// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Storage adapter for the unchanged SwiftUI ChatBackgroundView shared with the phone target.
/// Mac preferences stay in this app's local defaults domain.
enum AppTheme {
    static let paletteKey = "bipe.settings.palette"
    static let chatBackgroundKey = "bipe.settings.chatBackground"
    static var defaults: UserDefaults { .standard }

    static func palette(in store: UserDefaults) -> BipePalette {
        BipePalette(rawValue: store.string(forKey: paletteKey) ?? "") ?? .standard
    }
    static var stored: BipePalette { palette(in: defaults) }
    static var storedChatBackground: BipeChatBackground {
        BipeChatBackground(rawValue: defaults.string(forKey: chatBackgroundKey) ?? "") ?? .standard
    }
    @MainActor static func load() { apply(stored) }
    @MainActor static func save(_ palette: BipePalette) {
        defaults.set(palette.rawValue, forKey: paletteKey)
        apply(palette)
    }
    static func save(_ background: BipeChatBackground) { defaults.set(background.rawValue, forKey: chatBackgroundKey) }
    @MainActor private static func apply(_ palette: BipePalette) {
        BipeTheme.shared.palette = palette
        // Change the running Dock icon without writing into the signed application bundle.
        NSApplication.shared.applicationIconImage = NSImage(named: "BipePalette-" + palette.rawValue)
    }
}

/// Spec app-colors: generated choice order, labels and defaults; the phone's actual background renderer.
struct MacColorsView: View {
    @AppStorage(AppTheme.paletteKey) private var palette = BipePalette.standard.rawValue
    @AppStorage(AppTheme.chatBackgroundKey) private var background = BipeChatBackground.standard.rawValue
    @ScaledMetric(relativeTo: .body) private var previewSize: CGFloat = 44
    var body: some View {
        Form {
            Section {
                ForEach(BipePalette.allCases, id: \.self) { choice in
                    Button { AppTheme.save(choice); SyncedSettings.changed(BipeSyncedSettings.palette) } label: {
                        HStack(spacing: 16) {
                            Image("BipePalette-" + choice.rawValue).resizable().scaledToFit()
                                .frame(width: previewSize, height: previewSize).accessibilityHidden(true)
                            Text(choice.title)
                            Spacer()
                            if palette == choice.rawValue { Image(systemName: "checkmark").accessibilityHidden(true) }
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityAddTraits(palette == choice.rawValue ? .isSelected : [])
                }
            } header: { Text(BipeStrings.chatsColors) } footer: { Text(BipeStrings.chatsColorsFooter) }
            Section {
                ForEach(BipeChatBackground.allCases, id: \.self) { choice in
                    Button { AppTheme.save(choice); SyncedSettings.changed(BipeSyncedSettings.chatBackground) } label: {
                        HStack(spacing: 16) {
                            ChatBackgroundView(option: choice).frame(width: previewSize * 0.7, height: previewSize)
                                .clipShape(RoundedRectangle(cornerRadius: previewSize * 0.18))
                                .overlay(RoundedRectangle(cornerRadius: previewSize * 0.18).stroke(BipeColor.lineSubtle))
                                .accessibilityHidden(true)
                            Text(choice.title)
                            Spacer()
                            if background == choice.rawValue { Image(systemName: "checkmark").accessibilityHidden(true) }
                        }.contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityAddTraits(background == choice.rawValue ? .isSelected : [])
                }
            } header: { Text(BipeStrings.chatsBackground) } footer: { Text(BipeStrings.chatsBackgroundFooter) }
        }.formStyle(.grouped).navigationTitle(BipeStrings.chatsColors)
    }
}
