// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// Plays an animated UIImage (an animated WebP sticker, ADR 0205) in a loop. SwiftUI's Image shows only its
/// first frame. Callers show this view only when the sticker may play: not under Reduce Motion or the data
/// saver unless the person tapped play (spec timeline-media notes).
struct AnimatedImageView: UIViewRepresentable {
    let image: UIImage

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        // The SwiftUI frame sets the size, not the image.
        for axis in [NSLayoutConstraint.Axis.horizontal, .vertical] {
            view.setContentHuggingPriority(.defaultLow, for: axis)
            view.setContentCompressionResistancePriority(.defaultLow, for: axis)
        }
        view.isAccessibilityElement = false
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        // A UIImageView plays an animated UIImage by itself, in a loop.
        if view.image !== image {
            view.image = image
        }
    }
}

extension UIImage {
    /// The still to show when an animated image may not play: its first frame.
    var firstFrame: UIImage {
        images?.first ?? self
    }

    var isAnimatedImage: Bool {
        (images?.count ?? 0) > 1
    }
}
