// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written from the reference `normalize()` in bipe-shared tools/lib/registries.mjs. Cases: test-vectors/normalization.json.

import Foundation

/// Folds a display name for comparison with reserved names (security rule 8).
public nonisolated enum NameNormalizer {
    /// NFKD, strip combining marks, lowercase, fold homoglyphs (registries/homoglyphs.json), collapse whitespace, trim.
    public static func normalize(_ input: String) -> String {
        let scalars = input.decomposedStringWithCompatibilityMapping.unicodeScalars.filter { !isMark($0) }
        let lowered = String(String.UnicodeScalarView(scalars)).lowercased()
        let folded = String(lowered.map { BipeRegistries.homoglyphs[$0] ?? $0 })
        return folded
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    /// The category when the name is a reserved name after ``normalize(_:)`` (exact match), else `nil`.
    public static func reservedCategory(_ name: String) -> ReservedNameCategory? {
        BipeRegistries.reservedNames[normalize(name)]
    }

    private static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark: true
        default: false
        }
    }
}
