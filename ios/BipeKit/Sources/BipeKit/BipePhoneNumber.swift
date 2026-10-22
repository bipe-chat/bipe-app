// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Phone numbers for contact discovery: Brazilian mobiles in any local format, other countries only when written with `+` or `00`. Rule: bipe-shared/registries/phone-numbers.json (generated);
/// cases: test-vectors/phone-numbers.json. Same result as the API's `normalizePhone`.
public nonisolated enum BipePhoneNumber {
    /// E.164 (`+5511987654321`) from an address-book entry, or `nil`.
    public static func normalize(_ text: String) -> String? {
        let head = String(text.prefix(BipeRegistries.phoneMaxInputLength))
            .replacingOccurrences(of: "(0)", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var plus = head.filter { $0.isASCII && ($0.isNumber || $0 == "+") }.hasPrefix("+")
        var digits = head.filter { $0.isASCII && $0.isNumber }
        if !plus, digits.hasPrefix("00") {
            plus = true
            digits.removeFirst(2)
        }
        let countryCode = BipeRegistries.phoneCountryCode
        let national = BipeRegistries.phoneMobileLength + 2
        if plus {
            guard digits.hasPrefix(countryCode) else {
                // Another country: E.164 as written, length checked only.
                let valid = (BipeRegistries.phoneInternationalMinDigits...BipeRegistries.phoneInternationalMaxDigits).contains(digits.count) && !digits.hasPrefix("0")
                return valid ? "+\(digits)" : nil
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
