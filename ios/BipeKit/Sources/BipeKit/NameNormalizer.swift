// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Port of `normalize()` in bipe-shared/tools/lib/registries.mjs.
/// Must match bipe-shared/test-vectors/normalization.json.
public nonisolated enum NameNormalizer {
    /// NFKD, strip combining marks, lowercase, fold homoglyphs, collapse whitespace, trim.
    /// Pass an empty `homoglyphs` map to skip folding (scam phrase matching does this).
    public static func normalize(_ input: String,
                                 homoglyphs: [Character: Character] = BipeRegistries.homoglyphs) -> String {
        var stripped = String.UnicodeScalarView()
        for scalar in input.decomposedStringWithCompatibilityMapping.unicodeScalars where !isMark(scalar) {
            stripped.append(scalar)
        }
        // Locale-free and context-sensitive like JS toLowerCase(). Swift's lowercased() misses final sigma (Σ -> ς).
        let lowered = String(stripped).lowercased(with: nil)
        
        var result = String.UnicodeScalarView()
        var pendingSpace = false
        // JS iterates code points, so fold per scalar, not per grapheme cluster.
        for scalar in lowered.unicodeScalars {
            if isJavaScriptWhitespace(scalar) {
                pendingSpace = true
                continue
            }
            if pendingSpace, !result.isEmpty {
                result.append(" ")
            }
            pendingSpace = false
            if let folded = homoglyphs[Character(scalar)] {
                result.append(contentsOf: folded.unicodeScalars)
            } else {
                result.append(scalar)
            }
        }
        return String(result)
    }
    
    /// Category of a reserved display name, or `nil` when the name is free to use.
    public static func reservedCategory(for displayName: String) -> ReservedNameCategory? {
        BipeRegistries.reservedNames[normalize(displayName)]
    }
    
    // MARK: - Private
    
    private static func isMark(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .nonspacingMark, .spacingMark, .enclosingMark:
            true
        default:
            false
        }
    }
    
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
