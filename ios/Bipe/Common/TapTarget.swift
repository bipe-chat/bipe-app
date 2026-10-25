// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// The smallest tap target: 44 x 44 pt (bipe/docs/design/system.md, bipe-chat/bipe#498).
enum BipeTapTarget {
    static let minimum: CGFloat = 44
}

extension View {
    /// Makes the view at least 44 x 44 pt to tap, whatever the size of the icon in it.
    /// Put it on the label of a Button, inside the button, so that the hit area grows.
    func bipeTapTarget() -> some View {
        frame(minWidth: BipeTapTarget.minimum, minHeight: BipeTapTarget.minimum)
            .contentShape(Rectangle())
    }
}
