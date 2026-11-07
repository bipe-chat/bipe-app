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
            (mark.isSuspended ? PhosphorIcon.sealWarning : .sealCheck).image
                .resizable()
                .frame(width: size, height: size)
                .foregroundStyle(mark.tint)
                .accessibilityLabel(mark.label)
        }
    }
}
