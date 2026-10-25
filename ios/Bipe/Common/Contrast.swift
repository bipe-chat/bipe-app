// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

extension View {
    /// A blur behind bars over media, or a solid surface under Reduce Transparency (bipe-chat/bipe#497).
    /// BipeColor already switches to the high-contrast tokens under Increase Contrast.
    func bipeMaterial<S: Shape>(in shape: S) -> some View {
        modifier(BipeMaterial(shape: shape))
    }
}

private struct BipeMaterial<S: Shape>: ViewModifier {
    let shape: S
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    func body(content: Content) -> some View {
        if reduceTransparency {
            content.background(BipeColor.surfaceRaised, in: shape)
        } else {
            content.background(.ultraThinMaterial, in: shape)
        }
    }
}
