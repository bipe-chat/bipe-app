// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipePIN.swift).

import Foundation

/// The contract's `PinProblem`. Same ids as bipe-shared `pinProblem()`.
public enum BipePinProblem: String, Sendable, Equatable {
    case notDigits = "not_digits"
    case wrongLength = "wrong_length"
    case repeatedDigit = "repeated_digit"
    case sequence
    case date
    
    public var localizedMessage: String {
        switch self {
        case .notDigits: BipeStrings.pinProblemNotDigits
        case .wrongLength: BipeStrings.pinProblemWrongLength(String(BipeRegistries.pinLength))
        case .repeatedDigit: BipeStrings.pinProblemRepeatedDigit
        case .sequence: BipeStrings.pinProblemSequence
        case .date: BipeStrings.pinProblemDate
        }
    }
}

/// The 8-digit PIN of a device-first account (ADR 0057). Rule: bipe-shared/registries/accounts.json,
/// reference `pinProblem()` in bipe-shared/tools/lib/registries.mjs, cases in test-vectors/pins.json.
/// The server checks the PIN again; it is the authority.
public nonisolated enum BipePIN {
    /// `nil` when the PIN is strong enough. Checks in the order of the shared rule.
    public static func problem(for pin: String) -> BipePinProblem? {
        guard pin.unicodeScalars.allSatisfy({ ("0"..."9").contains($0) }) else { return .notDigits }
        guard pin.count == BipeRegistries.pinLength else { return .wrongLength }
        let digits = pin.unicodeScalars.map { Int($0.value) - 48 }
        if digits.allSatisfy({ $0 == digits[0] }) {
            return .repeatedDigit
        }
        // Up (step 1) or down (step 9, which is minus 1 modulo 10), with wrap: 78901234 and 21098765 count.
        for step in [1, 9] where digits.indices.dropFirst().allSatisfy({ digits[$0] == (digits[$0 - 1] + step) % 10 }) {
            return .sequence
        }
        let number = { (from: Int, to: Int) in digits[from..<to].reduce(0) { $0 * 10 + $1 } }
        if isDate(year: number(4, 8), month: number(2, 4), day: number(0, 2))
            || isDate(year: number(0, 4), month: number(4, 6), day: number(6, 8)) {
            return .date
        }
        return nil
    }
    
    /// Keeps digits only and cuts at the PIN length. For number-pad fields that can still receive pasted text.
    public static func sanitize(_ input: String) -> String {
        String(input.filter(\.isASCIIDigit).prefix(BipeRegistries.pinLength))
    }
    
    private static func isDate(year: Int, month: Int, day: Int) -> Bool {
        guard (BipeRegistries.pinDateYearMin...BipeRegistries.pinDateYearMax).contains(year), (1...12).contains(month), day >= 1 else {
            return false
        }
        let isLeap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        let days = [31, isLeap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1]
        return day <= days
    }
}

private extension Character {
    var isASCIIDigit: Bool {
        ("0"..."9").contains(self)
    }
}
