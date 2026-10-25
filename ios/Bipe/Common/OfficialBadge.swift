// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// The Bipe badge after the name of an official account (ADR 0117, specs chat-list, chat-timeline, person-profile).
/// Callers show it only when BipeOfficialAccounts.isOfficial(userId) is true.
struct OfficialBadge: View {
    var font: Font = .caption2

    var body: some View {
        Text(BipeStrings.badgeOfficial)
            .font(font.weight(.semibold))
            .foregroundStyle(BipeColor.trustVerifiedInk)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(Capsule().fill(BipeColor.trustVerifiedWash))
            .overlay(Capsule().stroke(BipeColor.trustVerifiedLine))
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(BipeStrings.badgeOfficialA11y)
    }
}
