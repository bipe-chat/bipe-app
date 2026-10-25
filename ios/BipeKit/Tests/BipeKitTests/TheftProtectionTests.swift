// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// The phone ID of PUT /v1/me/phone (bipe-chat/bipe#268).
struct TheftProtectionTests {
    @Test
    func phoneIdIsKeptAndValid() throws {
        let defaults = try #require(UserDefaults(suiteName: UUID().uuidString))
        let first = BipePhoneID.current(in: defaults)
        #expect(first.count == 22)
        #expect(BipePhoneID.isValid(first))
        #expect(BipePhoneID.current(in: defaults) == first)
    }

    @Test
    func badPhoneIdIsReplaced() throws {
        let defaults = try #require(UserDefaults(suiteName: UUID().uuidString))
        defaults.set("short", forKey: BipePhoneID.key)
        let id = BipePhoneID.current(in: defaults)
        #expect(id != "short")
        #expect(BipePhoneID.isValid(id))
        #expect(!BipePhoneID.isValid("has space in it 1234"))
    }
}
