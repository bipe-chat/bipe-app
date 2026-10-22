// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

#if canImport(UIKit)
import UIKit

/// Plays the haptics of the generated `BipeHaptic` (ADR 0134, registries/haptics.json). The only place in the app
/// that touches a UIKit feedback generator. The system haptics setting wins (design/haptics.md rule 6).
@MainActor
public enum BipeHaptics {
    public static func play(_ haptic: BipeHaptic) {
        switch haptic.primitive {
        case .selection:
            UISelectionFeedbackGenerator().selectionChanged()
        case .impact(let style, let intensity):
            UIImpactFeedbackGenerator(style: style).impactOccurred(intensity: intensity)
        case .notification(let type):
            UINotificationFeedbackGenerator().notificationOccurred(type)
        }
    }
}
#endif
