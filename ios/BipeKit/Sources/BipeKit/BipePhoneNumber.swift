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

    /// An E.164 number formatted for reading (ADR 0191): `+5511987654321` gives `+55 11 98765-4321`. The first entry of
    /// `BipeRegistries.phoneDisplayFormats` that fits wins; else the E.164 number as it is. Other text comes back unchanged.
    /// Cases: test-vectors/phone-numbers.json `display`.
    public static func display(_ e164: String) -> String {
        guard e164.hasPrefix("+"), e164.count > 1, e164.dropFirst().allSatisfy({ $0.isASCII && $0.isNumber }) else { return e164 }
        let digits = String(e164.dropFirst())
        for format in BipeRegistries.phoneDisplayFormats where digits.hasPrefix(format.countryCode) {
            let rest = Array(digits.dropFirst(format.countryCode.count))
            guard String(rest).hasPrefix(format.prefix), format.pattern.filter({ $0 == "#" }).count == rest.count else { continue }
            var index = 0
            let body = String(format.pattern.map { character -> Character in
                guard character == "#" else { return character }
                defer { index += 1 }
                return rest[index]
            })
            return "+\(format.countryCode) \(body)"
        }
        return e164
    }

    /// A number as saved in the address book, for reading: formatted when it normalises, else as saved.
    public static func displaySaved(_ text: String) -> String {
        normalize(text).map(display) ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
