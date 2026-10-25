// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Spec phone-link-status (bipe-chat/bipe#470): the thresholds come from registries/phone-numbers.json.
struct PhoneLinkStateTests {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private let day: TimeInterval = 86_400
    
    private func phone(linked: Bool = true, confirmIn days: Double?) -> BipeMyPhone {
        BipeMyPhone(linked: linked, discoverableByPhone: true, linkedAt: now, confirmBy: days.map { now.addingTimeInterval($0 * day) })
    }
    
    @Test
    func states() {
        let warn = Double(BipeRegistries.phoneLinkConfirmWarnDays)
        #expect(BipePhoneLinkState(phone: phone(linked: false, confirmIn: nil), now: now) == .notLinked)
        #expect(BipePhoneLinkState(phone: phone(confirmIn: warn + 1), now: now) == .linked(until: now.addingTimeInterval((warn + 1) * day)))
        #expect(BipePhoneLinkState(phone: phone(confirmIn: warn), now: now) == .confirmSoon(by: now.addingTimeInterval(warn * day)))
        #expect(BipePhoneLinkState(phone: phone(confirmIn: 0.5), now: now).needsAttention)
        #expect(BipePhoneLinkState(phone: phone(confirmIn: 0), now: now) == .expired(since: now))
        #expect(BipePhoneLinkState(phone: phone(confirmIn: -10), now: now).needsAttention)
        #expect(!BipePhoneLinkState(phone: phone(confirmIn: 200), now: now).needsAttention)
    }
}
