// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// The compact seal next to a name (spec person-profile notes, bipe#596): the same mark in every place.
/// It reads the session's seal store from the environment, verified on the device. No store or no mark: nothing shows.
struct SealMark: View {
    let userId: String?
    var size: CGFloat = 16
    @Environment(\.bipeCompanySeals) private var seals

    var body: some View {
        if let userId, let mark = seals?.mark(for: userId) {
            icon(mark).image
                .resizable()
                .frame(width: size, height: size)
                .foregroundStyle(tint(mark))
                .accessibilityLabel(Self.label(mark))
        }
    }

    private func icon(_ mark: BipeSealMark) -> PhosphorIcon {
        switch mark {
        case .person, .company: .sealCheck
        case .personSuspended, .companySuspended: .sealWarning
        }
    }

    private func tint(_ mark: BipeSealMark) -> Color {
        switch mark {
        case .person, .company: BipeColor.trustVerifiedInk
        case .personSuspended, .companySuspended: BipeColor.trustCautionInk
        }
    }

    static func label(_ mark: BipeSealMark) -> String {
        switch mark {
        case .person: BipeStrings.badgeVerified
        case .company: BipeStrings.badgeCompany
        case .personSuspended: BipeStrings.badgeSuspended
        case .companySuspended: BipeStrings.badgeCompanySuspended
        }
    }
}
