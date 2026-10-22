// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Looks up a generated bipe-shared string by its key, for code that gets keys from the generated registries
/// (for example ``BipeOnboardingStart/titleKey``). Typed access goes through ``BipeStrings``.
public enum BipeLocalized {
    public static func string(_ key: String, _ arguments: CVarArg...) -> String {
        let format = NSLocalizedString(key, tableName: "Bipe", bundle: .module, comment: "")
        return arguments.isEmpty ? format : String(format: format, arguments: arguments)
    }
}
