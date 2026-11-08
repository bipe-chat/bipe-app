// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
#if !os(macOS)
import Phosphor
#endif

/// Spec attachment-picker: one ordered list, filtered by room permissions, Notes and implemented platform capabilities.
enum AttachmentChoice: String, CaseIterable, Hashable, Identifiable {
    case photos, camera, file, location, sticker, poll
    var id: String { rawValue }
    var title: String {
        switch self {
        case .photos: BipeStrings.appAttachPhotos
        case .camera: BipeStrings.appAttachCamera
        case .file: BipeStrings.appAttachFile
        case .location: BipeStrings.appAttachLocation
        case .sticker: BipeStrings.actionStickers
        case .poll: BipeStrings.appAttachPoll
        }
    }
    var asset: String {
        switch self {
        case .photos: "image"
        case .camera: "camera"
        case .file: "file"
        case .location: ""
        case .sticker: "sticker"
        case .poll: "chart-bar"
        }
    }
    static func visible(canPoll: Bool, isNotes: Bool, supported: Set<Self> = Set(AttachmentChoice.allCases)) -> [Self] {
        allCases.filter { supported.contains($0) && ($0 != .poll || canPoll) && (!isNotes || [.photos, .camera, .file].contains($0)) }
    }
}

struct AttachmentMenu<Label: View>: View {
    let canPoll: Bool
    var isNotes = false
    var supported = Set(AttachmentChoice.allCases)
    let choose: (AttachmentChoice) -> Void
    @ViewBuilder let label: () -> Label

    var body: some View {
        Menu {
            ForEach(AttachmentChoice.visible(canPoll: canPoll, isNotes: isNotes, supported: supported)) { choice in
                Button { choose(choice) } label: { row(choice) }
            }
        } label: { label() }
        .menuOrder(.fixed)
        .accessibilityIdentifier(BipeScreen.attachmentPicker.rawValue)
        #if os(macOS)
        .menuStyle(.borderlessButton).menuIndicator(.hidden)
        .fixedSize().frame(width: BipeTapTarget.minimum, height: BipeTapTarget.minimum)
        .bipeGlass(in: Circle())
        #endif
    }

    @ViewBuilder private func row(_ choice: AttachmentChoice) -> some View {
        if choice == .location {
            SwiftUI.Label(choice.title, systemImage: "mappin.and.ellipse")
        } else {
            #if os(macOS)
            SwiftUI.Label { Text(choice.title) } icon: {
                Image(nsImage: MacComposerArtwork.image(asset: choice.asset)).renderingMode(.template)
            }
            #else
            IconLabel(icon: icon(choice), text: choice.title)
            #endif
        }
    }

    #if !os(macOS)
    private func icon(_ choice: AttachmentChoice) -> PhosphorIcon {
        switch choice {
        case .photos: .image
        case .camera: .camera
        case .file: .file
        case .sticker: .sticker
        case .poll: .chartBar
        case .location: .plus // The location row uses the same native pin as before.
        }
    }
    #endif
}
