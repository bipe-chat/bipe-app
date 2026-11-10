// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
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
    /// `nil` when the PIN is strong enough (bipe-core `pinProblem`).
    public static func problem(for pin: String) -> BipePinProblem? {
        pinProblem(pin: pin).map { problem in
            switch problem {
            case .notDigits: .notDigits
            case .wrongLength: .wrongLength
            case .repeatedDigit: .repeatedDigit
            case .sequence: .sequence
            case .date: .date
            }
        }
    }
    
    /// Keeps digits only and cuts at the PIN length. For number-pad fields that can still receive pasted text.
    public static func sanitize(_ input: String) -> String {
        String(input.filter(\.isASCIIDigit).prefix(BipeRegistries.pinLength))
    }
}

private extension Character {
    var isASCIIDigit: Bool {
        ("0"..."9").contains(self)
    }
}
