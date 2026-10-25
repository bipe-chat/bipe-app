// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct RecoveryReminderTests {
    private let day: TimeInterval = 86_400
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func waitsFirstAfterDaysThenShows() {
        let first = TimeInterval(BipeRegistries.recoveryReminderFirstAfterDays) * day
        #expect(!BipeRecoveryReminder.isDue(now: start.addingTimeInterval(first - 1), since: start, snoozedUntil: nil))
        #expect(BipeRecoveryReminder.isDue(now: start.addingTimeInterval(first), since: start, snoozedUntil: nil))
    }

    @Test func snoozesFollowTheListAndTheLastRepeats() {
        let days = BipeRegistries.recoveryReminderSnoozeDays
        for (count, expected) in (days + [days.last!, days.last!]).enumerated() {
            #expect(BipeRecoveryReminder.snoozedUntil(now: start, count: count) == start.addingTimeInterval(TimeInterval(expected) * day))
        }
        let until = BipeRecoveryReminder.snoozedUntil(now: start, count: 0)
        #expect(!BipeRecoveryReminder.isDue(now: until.addingTimeInterval(-1), since: .distantPast, snoozedUntil: until))
        #expect(BipeRecoveryReminder.isDue(now: until, since: .distantPast, snoozedUntil: until))
    }
}
