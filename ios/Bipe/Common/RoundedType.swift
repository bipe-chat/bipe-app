// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import SwiftUI
import UIKit

/// Rounded type for all app UI text (ADR 0159): SF Rounded, with the system text styles, weights and Dynamic Type.
/// Monospaced codes keep `.monospaced`, which wins over the inherited design.
extension View {
    /// Put on the root view of the app and of each extension with UI.
    func bipeRoundedType() -> some View {
        fontDesign(.rounded)
    }
}

enum RoundedType {
    /// Navigation bar titles are UIKit text that `fontDesign` does not reach. Call once before the first view.
    /// Tab bar labels stay as the system draws them (ADR 0149).
    static func applyToBars() {
        let bar = UINavigationBar.appearance()
        bar.titleTextAttributes = [.font: font(.headline, weight: .semibold)]
        bar.largeTitleTextAttributes = [.font: font(.largeTitle, weight: .bold)]
    }

    private static func font(_ style: UIFont.TextStyle, weight: UIFont.Weight) -> UIFont {
        // The default size; UIFontMetrics then scales it for the current Dynamic Type size.
        let base = UIFont.preferredFont(forTextStyle: style, compatibleWith: UITraitCollection(preferredContentSizeCategory: .large))
        let weighted = base.fontDescriptor.addingAttributes([.traits: [UIFontDescriptor.TraitKey.weight: weight]])
        let rounded = weighted.withDesign(.rounded) ?? weighted
        return UIFontMetrics(forTextStyle: style).scaledFont(for: UIFont(descriptor: rounded, size: 0))
    }
}
