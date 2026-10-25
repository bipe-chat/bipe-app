// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// Synthetic rooms for the chat-list row date (bipe-chat/bipe#592). The same cases run in RowActivityTest.kt.
struct RowActivityTests {
    private typealias Event = BipeRowActivity.LocalEvent

    @Test func sdkLatestEventWins() {
        #expect(BipeRowActivity.needsLocalTimeline(sdkLatestMs: 1_700_000_000_000) == false)
        let result = BipeRowActivity.resolve(sdkLatestMs: 1_700_000_000_000,
                                             localEvents: [Event(timestampMs: 1_800_000_000_000, kind: .message)],
                                             knownMs: 1_900_000_000_000)
        #expect(result == 1_700_000_000_000)
    }

    /// The device test of 25 Oct 2026: an unapproved phone, every recent event undecryptable, no SDK latest event.
    @Test func roomWhoseLatestEventIsUndecryptableKeepsItsDate() {
        #expect(BipeRowActivity.needsLocalTimeline(sdkLatestMs: nil))
        let events = [Event(timestampMs: 1_700_000_000_000, kind: .message),
                      Event(timestampMs: 1_700_000_500_000, kind: .message), // undecryptable
                      Event(timestampMs: 1_700_000_900_000, kind: .other)] // a member left
        #expect(BipeRowActivity.resolve(sdkLatestMs: nil, localEvents: events, knownMs: nil) == 1_700_000_500_000)
    }

    @Test func roomWithOnlyStateEventsUsesTheNewestEvent() {
        let events = [Event(timestampMs: 1_600_000_000_000, kind: .other), Event(timestampMs: 1_600_000_100_000, kind: .other)]
        #expect(BipeRowActivity.resolve(sdkLatestMs: nil, localEvents: events, knownMs: nil) == 1_600_000_100_000)
    }

    @Test func knownTimeStaysWhenTheTimelineIsEmptyOrOlder() {
        #expect(BipeRowActivity.resolve(sdkLatestMs: nil, localEvents: [], knownMs: 1_650_000_000_000) == 1_650_000_000_000)
        let older = [Event(timestampMs: 1_640_000_000_000, kind: .message)]
        #expect(BipeRowActivity.resolve(sdkLatestMs: nil, localEvents: older, knownMs: 1_650_000_000_000) == 1_650_000_000_000)
    }

    /// No local data: the row has no date. It never takes the current time.
    @Test func unknownStaysUnknown() {
        #expect(BipeRowActivity.resolve(sdkLatestMs: nil, localEvents: [], knownMs: nil) == nil)
    }
}
