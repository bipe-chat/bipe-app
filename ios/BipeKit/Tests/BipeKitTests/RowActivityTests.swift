// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// Synthetic rooms for the chat-list row date and text (bipe-chat/bipe#592). The same cases run in RowActivityTest.kt.
struct RowActivityTests {
    private typealias Event = BipeRowActivity.LocalEvent
    private typealias SDK = BipeRowActivity.SDKLatest

    @Test func sdkLatestEventWinsWhenItIsTheNewest() {
        let row = BipeRowActivity.resolve(sdk: SDK(timestampMs: 1_700_000_000_000, previewText: "oi", isIncoming: true),
                                          localEvents: [Event(timestampMs: 1_600_000_000_000, kind: .message, previewText: "old")],
                                          known: .init(timestampMs: 1_900_000_000_000, previewText: nil, latestIncomingMs: nil))
        #expect(row == .init(timestampMs: 1_700_000_000_000, previewText: "oi", latestIncomingMs: 1_700_000_000_000))
    }

    /// The device test of 26 Oct 2026: the message arrived before its room key, so the SDK kept the own join. The
    /// timeline decrypted the message later.
    @Test func newerDecryptedLocalMessageReplacesTheSdkJoin() {
        let join = SDK(timestampMs: 1_700_000_000_000, previewText: nil, isIncoming: false)
        let events = [Event(timestampMs: 1_700_000_000_000, kind: .other),
                      Event(timestampMs: 1_700_000_060_000, kind: .message, previewText: "oi", isOwn: false)]
        let row = BipeRowActivity.resolve(sdk: join, localEvents: events, known: nil)
        #expect(row == .init(timestampMs: 1_700_000_060_000, previewText: "oi", latestIncomingMs: 1_700_000_060_000))
    }

    /// A newer message that still does not decrypt shows the undecryptable label with its own time.
    @Test func newerUndecryptableLocalMessageShowsItsLabel() {
        let sdk = SDK(timestampMs: 1_700_000_000_000, previewText: "before", isIncoming: true)
        let events = [Event(timestampMs: 1_700_000_500_000, kind: .message, previewText: "undecryptable", isOwn: false)]
        let row = BipeRowActivity.resolve(sdk: sdk, localEvents: events, known: nil)
        #expect(row == .init(timestampMs: 1_700_000_500_000, previewText: "undecryptable", latestIncomingMs: 1_700_000_500_000))
    }

    /// An own newer message does not count as incoming (the archive keeps the chat archived).
    @Test func ownNewerLocalMessageIsNotIncoming() {
        let sdk = SDK(timestampMs: 1_700_000_000_000, previewText: "oi", isIncoming: true)
        let events = [Event(timestampMs: 1_700_000_100_000, kind: .message, previewText: "tudo bem?", isOwn: true)]
        let row = BipeRowActivity.resolve(sdk: sdk, localEvents: events, known: nil)
        #expect(row == .init(timestampMs: 1_700_000_100_000, previewText: "tudo bem?", latestIncomingMs: nil))
    }

    /// A newer state event (a member left) never replaces the SDK latest event.
    @Test func newerStateEventKeepsTheSdkLatestEvent() {
        let sdk = SDK(timestampMs: 1_700_000_000_000, previewText: "oi", isIncoming: false)
        let row = BipeRowActivity.resolve(sdk: sdk, localEvents: [Event(timestampMs: 1_700_000_900_000, kind: .other)], known: nil)
        #expect(row == .init(timestampMs: 1_700_000_000_000, previewText: "oi", latestIncomingMs: nil))
    }

    /// The device test of 25 Oct 2026: an unapproved phone, every recent event undecryptable, no SDK latest event.
    @Test func roomWhoseLatestEventIsUndecryptableKeepsItsDate() {
        let events = [Event(timestampMs: 1_700_000_000_000, kind: .message, previewText: "a"),
                      Event(timestampMs: 1_700_000_500_000, kind: .message, previewText: "undecryptable"),
                      Event(timestampMs: 1_700_000_900_000, kind: .other)] // a member left
        let row = BipeRowActivity.resolve(sdk: nil, localEvents: events, known: nil)
        #expect(row.timestampMs == 1_700_000_500_000)
        #expect(row.previewText == "undecryptable")
    }

    @Test func roomWithOnlyStateEventsUsesTheNewestEvent() {
        let events = [Event(timestampMs: 1_600_000_000_000, kind: .other), Event(timestampMs: 1_600_000_100_000, kind: .other)]
        #expect(BipeRowActivity.resolve(sdk: nil, localEvents: events, known: nil)
            == .init(timestampMs: 1_600_000_100_000, previewText: nil, latestIncomingMs: nil))
    }

    /// A changed room is read again: its local events are empty meanwhile, and the row keeps its time and text.
    @Test func knownRowStaysWhenTheTimelineIsEmpty() {
        let known = BipeRowActivity.Row(timestampMs: 1_650_000_000_000, previewText: "oi", latestIncomingMs: 1_650_000_000_000)
        #expect(BipeRowActivity.resolve(sdk: nil, localEvents: [], known: known) == known)
    }

    /// A newer known time (for example from a call membership change) keeps the text of the newest local message.
    @Test func knownNewerTimeKeepsTheLocalText() {
        let known = BipeRowActivity.Row(timestampMs: 1_650_000_000_000, previewText: nil, latestIncomingMs: nil)
        let older = [Event(timestampMs: 1_640_000_000_000, kind: .message, previewText: "old")]
        #expect(BipeRowActivity.resolve(sdk: nil, localEvents: older, known: known)
            == .init(timestampMs: 1_650_000_000_000, previewText: "old", latestIncomingMs: 1_640_000_000_000))
    }

    /// MZ's device test of 27 Oct 2026 (build 205): no SDK latest event, the last event an own call. The row showed a
    /// date and no text.
    @Test func ownCallGivesTheRowItsText() {
        let events = [Event(timestampMs: 1_700_000_000_000, kind: .message, previewText: "oi"),
                      Event(timestampMs: 1_700_000_060_000, kind: .message, previewText: "Voice call", isOwn: true),
                      Event(timestampMs: 1_700_000_120_000, kind: .other)] // the call membership ended
        #expect(BipeRowActivity.resolve(sdk: nil, localEvents: events, known: nil)
            == .init(timestampMs: 1_700_000_060_000, previewText: "Voice call", latestIncomingMs: nil))
    }

    /// A newer message without a preview (deleted) never blanks the row.
    @Test func newerMessageWithoutPreviewKeepsTheSdkText() {
        let sdk = SDK(timestampMs: 1_700_000_000_000, previewText: "oi", isIncoming: true)
        let events = [Event(timestampMs: 1_700_000_100_000, kind: .message)]
        #expect(BipeRowActivity.resolve(sdk: sdk, localEvents: events, known: nil)
            == .init(timestampMs: 1_700_000_000_000, previewText: "oi", latestIncomingMs: 1_700_000_000_000))
    }

    /// An SDK latest event without a preview (a poll, a knock) keeps the text of the newest local message.
    @Test func sdkLatestEventWithoutPreviewKeepsTheLocalText() {
        let sdk = SDK(timestampMs: 1_700_000_100_000, previewText: nil, isIncoming: false)
        let events = [Event(timestampMs: 1_700_000_000_000, kind: .message, previewText: "oi")]
        #expect(BipeRowActivity.resolve(sdk: sdk, localEvents: events, known: nil)
            == .init(timestampMs: 1_700_000_100_000, previewText: "oi", latestIncomingMs: 1_700_000_000_000))
    }

    /// No local data: the row has no date. It never takes the current time.
    @Test func unknownStaysUnknown() {
        #expect(BipeRowActivity.resolve(sdk: nil, localEvents: [], known: nil)
            == .init(timestampMs: nil, previewText: nil, latestIncomingMs: nil))
    }
}
