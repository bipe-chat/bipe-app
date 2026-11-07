// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

extension BipeSealMark {
    /// The icon carries the level, never the colour alone (ADR 0275).
    var icon: PhosphorIcon {
        switch self {
        case .personState: .sealCheckFill
        case .personBank, .company: .sealCheck
        case .personSuspended, .companySuspended: .sealWarning
        }
    }
}

/// The compact seal next to a name (spec person-profile notes, bipe#596): the same mark in every place.
/// It reads the session's seal store from the environment, verified on the device. No store or no mark: nothing shows.
struct SealMark: View {
    let userId: String?
    var size: CGFloat = 16
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        if let userId, let mark = seals?.mark(for: userId) {
            mark.icon.image
                .resizable()
                .frame(width: size, height: size)
                .foregroundStyle(mark.tint)
                .accessibilityLabel(mark.label)
        }
    }
}

/// The compact seal where a tap opens person-seal for the strongest proof (ADR 0275 point 4): the profile and the chat
/// header. Any other mark, an amber seal too, stays a plain mark. A gesture and not a Button: the chat title is a Button.
struct PersonSealButton: View {
    let userId: String?
    var size: CGFloat = 16
    @Environment(\.bipeCompanySeals) private var seals
    @State private var opened: BipePersonProof?

    var body: some View {
        let strongest = userId.flatMap { seals?.strongestProof(for: $0) }
        Group {
            if let strongest {
                SealMark(userId: userId, size: size)
                    .contentShape(Rectangle())
                    .bipeTapTarget()
                    .highPriorityGesture(TapGesture().onEnded { opened = strongest })
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { opened = strongest }
            } else {
                SealMark(userId: userId, size: size)
            }
        }
        .sheet(item: $opened) { PersonSealSheet(proof: $0) }
        // Fail closed: the sheet closes when the badge stops verifying.
        .onChange(of: strongest == nil) { _, gone in
            if gone { opened = nil }
        }
    }
}
