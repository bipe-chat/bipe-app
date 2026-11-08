// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
#if os(macOS)
import AppKit
#else
import Phosphor
#endif

/// Spec composer: the Apple clients compile the same control, input, row and context-panel views.
enum BipeComposerGlyph: String, CaseIterable {
    case plus, check, microphone
    case send = "paper-plane-right"
    case reply = "arrow-bend-up-left"
    case edit = "pencil-simple"
    case close = "x"

    @MainActor var image: Image {
        #if os(macOS)
        Image(nsImage: MacComposerArtwork.image(asset: rawValue))
        #else
        let icon: PhosphorIcon
        switch self {
        case .plus: icon = .plus
        case .check: icon = .check
        case .microphone: icon = .microphone
        case .send: icon = .paperPlaneRight
        case .reply: icon = .arrowBendUpLeft
        case .edit: icon = .pencilSimple
        case .close: icon = .x
        }
        return icon.image
        #endif
    }
}

#if os(macOS)
/// AppKit menus can extract the source NSImage without its SwiftUI layout modifiers. Supply a real 22pt image.
@MainActor enum MacComposerArtwork {
    private static var images: [String: NSImage] = [:]

    static func image(asset: String) -> NSImage {
        if let image = images[asset] { return image }
        let size = NSSize(width: 22, height: 22)
        guard let source = NSImage(named: asset) else {
            assertionFailure("Missing composer icon asset: " + asset)
            return NSImage(size: size)
        }
        let image = NSImage(size: size, flipped: false) { rect in
            source.draw(in: rect)
            return true
        }
        image.isTemplate = true
        images[asset] = image
        return image
    }
}
#endif

struct BipeComposerControlIcon: View {
    let glyph: BipeComposerGlyph
    var enabled = true
    var accented = false
    /// The Mac Menu gets its surface outside the native control, which extracts only the label's image.
    var surface = true

    @ViewBuilder var body: some View {
        if surface { icon.bipeGlass(in: Circle(), tint: accented && enabled ? BipeColor.accentFill : nil) }
        else { icon }
    }

    private var icon: some View {
        glyph.image.resizable().scaledToFit().frame(width: 22, height: 22)
            .padding(11)
            .foregroundStyle(enabled ? (accented ? BipeColor.inkOnAccent : BipeColor.glyphPrimary) : BipeColor.inkDisabled)
            .bipeTapTarget()
    }
}

struct BipeComposerRow<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(alignment: .bottom, spacing: 8, content: content)
            .padding(.horizontal, 12).padding(.vertical, 8)
    }
}

struct BipeComposerInput<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        HStack(alignment: .bottom, spacing: 4, content: content)
            .padding(.leading, 16).padding(.trailing, 4)
            .frame(minHeight: BipeTapTarget.minimum)
            .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

struct BipeComposerPanel<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(spacing: 0, content: content)
            .padding(.bottom, 8)
            .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 12).padding(.top, 8)
    }
}

struct BipeComposerContextBar: View {
    let glyph: BipeComposerGlyph
    let title: String
    let preview: String?
    let onClose: () -> Void
    var body: some View {
        HStack(spacing: 10) {
            glyph.image.resizable().frame(width: 18, height: 18)
                .foregroundStyle(BipeColor.accentInk).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).composerCaption().bold().foregroundStyle(BipeColor.accentInk)
                if let preview { Text(preview).composerCaption().foregroundStyle(BipeColor.inkSecondary).lineLimit(1) }
            }
            Spacer()
            Button(action: onClose) {
                BipeComposerGlyph.close.image.resizable().frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.glyphSecondary).padding(6).bipeTapTarget()
            }.buttonStyle(.plain).accessibilityLabel(BipeStrings.appCancel)
        }.padding(.horizontal, 16).padding(.top, 8)
    }
}

private extension View {
    @ViewBuilder func composerCaption() -> some View {
        #if os(macOS)
        macFont(.caption)
        #else
        font(.caption)
        #endif
    }
}
