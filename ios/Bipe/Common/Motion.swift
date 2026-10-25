// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// withAnimation that honours Reduce Motion (bipe-chat/bipe#496): with the setting on, the change
/// happens at once and nothing slides, springs or scrolls by itself.
@MainActor
func withBipeAnimation<Result>(_ animation: Animation? = .default, _ body: () throws -> Result) rethrows -> Result {
    try withAnimation(UIAccessibility.isReduceMotionEnabled ? nil : animation, body)
}

extension View {
    /// .animation(_:value:) that honours Reduce Motion: no animation when the setting is on.
    func bipeAnimation<V: Equatable>(_ animation: Animation? = .default, value: V) -> some View {
        modifier(ReduceMotionAnimation(animation: animation, value: value))
    }
}

private struct ReduceMotionAnimation<V: Equatable>: ViewModifier {
    let animation: Animation?
    let value: V
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.animation(reduceMotion ? nil : animation, value: value)
    }
}

extension AnyTransition {
    /// A move under normal motion, a fade under Reduce Motion (Apple: replace slides with dissolves).
    @MainActor
    static func bipeMove(edge: Edge) -> AnyTransition {
        UIAccessibility.isReduceMotionEnabled ? .opacity : .move(edge: edge).combined(with: .opacity)
    }
}
