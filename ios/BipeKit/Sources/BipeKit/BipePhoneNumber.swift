// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written from the reference `normalizePhone()` in bipe-shared tools/lib/registries.mjs (rule: registries/phone-numbers.json).
// Cases: test-vectors/phone-numbers.json. The number is for discovery only, never identity (ADR 0015).

import Foundation

public nonisolated enum BipePhoneNumber {
    /// E.164 from an address-book entry, or `nil`: Brazilian mobiles in any local format,
    /// other countries only when written with "+" or "00".
    public static func normalize(_ text: String) -> String? {
        let head = String(text.prefix(BipeRegistries.phoneMaxInputLength))
            .replacingOccurrences(of: "(0)", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var plus = head.filter { $0.isASCIIDigit || $0 == "+" }.hasPrefix("+")
        var digits = head.filter(\.isASCIIDigit)
        if !plus, digits.hasPrefix("00") {
            plus = true
            digits.removeFirst(2)
        }
        let countryCode = BipeRegistries.phoneCountryCode
        let national = BipeRegistries.phoneMobileLength + 2
        if plus {
            if !digits.hasPrefix(countryCode) {
                // Another country: E.164 as written, length checked only.
                let ok = digits.count >= BipeRegistries.phoneInternationalMinDigits
                    && digits.count <= BipeRegistries.phoneInternationalMaxDigits
                    && !digits.hasPrefix("0")
                return ok ? "+\(digits)" : nil
            }
            digits.removeFirst(countryCode.count)
        } else if digits.count == national + 3, digits.hasPrefix("0") {
            digits.removeFirst(3) // trunk 0 + carrier code
        } else if digits.count == national + 1, digits.hasPrefix("0") {
            digits.removeFirst(1) // trunk 0
        } else if digits.count == national + countryCode.count, digits.hasPrefix(countryCode) {
            digits.removeFirst(countryCode.count)
        }
        guard digits.count == national else { return nil }
        let area = String(digits.prefix(2))
        let subscriber = String(digits.dropFirst(2))
        guard BipeRegistries.phoneAreaCodes.contains(area), subscriber.hasPrefix(BipeRegistries.phoneMobilePrefix) else { return nil }
        return "+\(countryCode)\(area)\(subscriber)"
    }
}

private extension Character {
    var isASCIIDigit: Bool {
        ("0"..."9").contains(self)
    }
}
