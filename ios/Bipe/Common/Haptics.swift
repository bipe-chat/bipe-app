// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

extension BipeHaptic {
    /// Plays the haptic with the UIKit generator its primitive names (ADR 0134).
    @MainActor
    func play() {
        switch primitive {
        case .selection:
            UISelectionFeedbackGenerator().selectionChanged()
        case .impact(let style, let intensity):
            UIImpactFeedbackGenerator(style: style).impactOccurred(intensity: intensity)
        case .notification(let type):
            UINotificationFeedbackGenerator().notificationOccurred(type)
        }
    }
}

extension View {
    /// Haptics of a PIN field (MZ, 8 Nov 2026: PIN inputs always have haptics): a tick for each digit typed or
    /// deleted, the error haptic (pin_wrong) when `error` turns on. A reset or a paste moves more than one digit
    /// and does not tick. The shared pad ticks on its own keys (PinPadView).
    func bipePinHaptics(count: Int, error: Bool = false) -> some View {
        onChange(of: count) { old, new in
            if abs(new - old) == 1 { BipeHaptics.play(.tick) }
        }
        .onChange(of: error) { _, isError in
            if isError { BipeHaptics.play(.error) }
        }
    }
}
