// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written from the reference `normalizeCnpj()` and `formatCnpj()` in bipe-shared tools/lib/registries.mjs (ADR 0042).
// Cases: test-vectors/cnpj.json. Numeric and alphanumeric CNPJs share one rule.

import Foundation

public nonisolated enum BipeCNPJ {
    private static let weights1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]
    private static let weights2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]

    /// The CNPJ as 14 uppercase characters, or `nil` when the format or a check digit is wrong.
    public static func normalize(_ input: String) -> String? {
        let value = input.filter { !".-/".contains($0) && !$0.isWhitespace }.uppercased()
        guard value.range(of: "^[0-9A-Z]{12}[0-9]{2}$", options: .regularExpression) != nil else { return nil }
        if Set(value).count == 1 {
            return nil
        }
        let base = Array(value.prefix(12))
        let first = checkDigit(base, weights: weights1)
        let second = checkDigit(base + Array(String(first)), weights: weights2)
        return value.hasSuffix("\(first)\(second)") ? value : nil
    }

    /// `12.ABC.345/01DE-35` from the 14 characters of a normalised CNPJ.
    public static func formatted(_ cnpj: String) -> String {
        let c = Array(cnpj)
        guard c.count == 14 else { return cnpj }
        return "\(String(c[0..<2])).\(String(c[2..<5])).\(String(c[5..<8]))/\(String(c[8..<12]))-\(String(c[12..<14]))"
    }

    private static func checkDigit(_ chars: [Character], weights: [Int]) -> Int {
        let sum = zip(chars, weights).reduce(0) { total, pair in
            total + (Int(pair.0.asciiValue ?? 48) - 48) * pair.1
        }
        let remainder = sum % 11
        return remainder < 2 ? 0 : 11 - remainder
    }
}
