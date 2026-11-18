// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

extension BipeSealMark {
    /// The Phosphor asset of the mark, as iOS SealMark: the icon carries the level, never the colour alone (ADR 0275).
    var assetName: String {
        switch self {
        case .personState: "seal-check-fill"
        case .personBank, .company: "seal-check"
        case .personSuspended, .companySuspended: "seal-warning"
        }
    }
}

/// The compact seal next to a name (spec person-profile notes, bipe#596), the same mark in every place, as iOS SealMark.
/// It reads the session's seal store from the environment, verified on the device. No store or no mark: nothing shows.
struct MacSealMark: View {
    let userID: String?
    var size: CGFloat = 16
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        if let userID, let mark = seals?.mark(for: userID) {
            Image(mark.assetName).renderingMode(.template).resizable().scaledToFit()
                .frame(width: size, height: size).foregroundStyle(mark.tint).accessibilityLabel(mark.label)
        }
    }
}

/// The Bipe badge after the name of an official account (ADR 0117, specs chat-list, chat-timeline), as iOS
/// OfficialBadge. Callers show it only when BipeOfficialAccounts.isOfficial(userID) is true.
struct MacOfficialBadge: View {
    var style: MacTextStyle = .caption2

    var body: some View {
        Text(BipeStrings.badgeOfficial)
            .macFont(style).fontWeight(.semibold)
            .foregroundStyle(BipeColor.trustVerifiedInk)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(Capsule().fill(BipeColor.trustVerifiedWash))
            .overlay(Capsule().strokeBorder(BipeColor.trustVerifiedLine))
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(BipeStrings.badgeOfficialA11y)
    }
}

/// The marks after a chat's name: the official badge of a DM with an official account, and the seal of a DM peer.
struct MacNameMarks: View {
    let isDm: Bool
    let peerID: String?

    var body: some View {
        if isDm, let peerID {
            if BipeOfficialAccounts.isOfficial(peerID) { MacOfficialBadge() }
            MacSealMark(userID: peerID)
        }
    }
}
