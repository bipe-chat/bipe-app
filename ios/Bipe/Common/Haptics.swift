// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import UIKit

extension BipeHaptic {
    /// Plays the haptic of the Bipe haptics language (ADR 0134, registries haptics).
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
