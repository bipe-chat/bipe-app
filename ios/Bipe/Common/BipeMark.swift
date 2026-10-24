// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI

/// Bipe's mark, the shape of the app icon: a rounded bubble whose top-leading corner stays tight as the
/// notch (identity.md, artwork-brief.md "seal tail"). The radii are fractions of the side, measured on
/// Assets.xcassets/AppIcon: 196 and 20 of a 528-point bubble. Use this shape for the brand; never a
/// generic speech-bubble glyph.
struct BipeMark: Shape {
    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let round = side * 196 / 528
        let notch = side * 20 / 528
        let square = CGRect(x: rect.midX - side / 2, y: rect.midY - side / 2, width: side, height: side)
        return UnevenRoundedRectangle(cornerRadii: .init(topLeading: notch, bottomLeading: round,
                                                         bottomTrailing: round, topTrailing: round),
                                      style: .continuous)
            .path(in: square)
    }
}
