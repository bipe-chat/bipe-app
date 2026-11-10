// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Phone numbers for contact discovery: Brazilian mobiles in any local format, other countries only when written with `+` or `00`. Rule: bipe-shared/registries/phone-numbers.json (generated);
/// cases: test-vectors/phone-numbers.json. Same result as the API's `normalizePhone`.
public nonisolated enum BipePhoneNumber {
    /// E.164 (`+5511987654321`) from an address-book entry, or `nil` (bipe-core `normalizePhone`).
    public static func normalize(_ text: String) -> String? {
        normalizePhone(text: text)
    }

    /// An E.164 number formatted for reading (ADR 0191), from bipe-core `displayPhone`. Other text comes back unchanged.
    public static func display(_ e164: String) -> String {
        displayPhone(e164: e164)
    }

    /// A number as saved in the address book, for reading: formatted when it normalises, else as saved.
    public static func displaySaved(_ text: String) -> String {
        normalize(text).map(display) ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
