// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Spec app-colors: the palette the user picked. It lives in the app group defaults, so the share extension shows
/// the same colours. BipeTheme holds the palette that the views read.
enum AppTheme {
    static let paletteKey = "bipe.settings.palette"

    private static var defaults: UserDefaults { UserDefaults(suiteName: AppConfig.appGroup) ?? .standard }

    /// The stored palette. A missing or unknown id is the default palette.
    static var stored: BipePalette {
        BipePalette(rawValue: defaults.string(forKey: paletteKey) ?? "") ?? .standard
    }

    /// Shows the stored palette. Call at launch, before the first view.
    static func load() {
        BipeTheme.shared.palette = stored
    }

    /// Keeps the palette on the phone and shows it at once.
    static func save(_ palette: BipePalette) {
        defaults.set(palette.rawValue, forKey: paletteKey)
        BipeTheme.shared.palette = palette
    }
}
