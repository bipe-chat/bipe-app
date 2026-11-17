// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// JSON text for the bipe-core functions that read Matrix event content (`message_data.rs`).
nonisolated enum CoreJSON {
    /// The JSON text of a `JSONSerialization` object or array, or `nil` when it is not valid JSON.
    static func text(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object), let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The object of JSON text, or `nil`.
    static func object(_ text: String?) -> [String: Any]? {
        guard let data = text?.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }
}
