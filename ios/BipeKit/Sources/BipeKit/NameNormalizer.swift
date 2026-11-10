// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// `normalize()` of bipe-shared/tools/lib/registries.mjs, run in bipe-core (`normalizeText`, ADR 0267).
/// Must match bipe-shared/test-vectors/normalization.json.
public nonisolated enum NameNormalizer {
    /// NFKD, strip combining marks, lowercase, fold homoglyphs, collapse whitespace, trim.
    /// `folding: false` skips the homoglyph map (scam phrase matching and handle suggestions do this).
    public static func normalize(_ input: String, folding: Bool = true) -> String {
        normalizeText(input: input, fold: folding ? .names : .plain)
    }
    
    /// Category of a reserved display name, or `nil` when the name is free to use.
    public static func reservedCategory(for displayName: String) -> ReservedNameCategory? {
        BipeRegistries.reservedNames[normalize(displayName)]
    }
    
    // MARK: - Private
    
    /// JS `\s` and `String.prototype.trim()`: WhiteSpace plus LineTerminator (ECMA-262).
    /// Not the same set as ICU `\s` or `Character.isWhitespace` (U+000B, U+FEFF, U+0085 differ).
    static func isJavaScriptWhitespace(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0xFEFF, 0x2028, 0x2029:
            true
        default:
            scalar.properties.generalCategory == .spaceSeparator
        }
    }
}
