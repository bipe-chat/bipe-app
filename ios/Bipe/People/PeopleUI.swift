// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// A list row with a Phosphor icon in front of its text.
struct IconLabel: View {
    let icon: PhosphorIcon
    let text: String
    var tint: Color = BipeColor.inkPrimary

    var body: some View {
        Label {
            Text(text)
                .foregroundStyle(tint)
        } icon: {
            icon.image
                .resizable()
                .frame(width: 20, height: 20)
                .foregroundStyle(tint)
        }
    }
}

extension UIImage {
    /// The centre square, drawn upright: the "square crop" of the profile photo specs.
    func centreSquare() -> UIImage {
        let side = min(size.width, size.height)
        guard side > 0, size.width != size.height else { return self }
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            draw(at: CGPoint(x: -(size.width - side) / 2, y: -(size.height - side) / 2))
        }
    }
}
