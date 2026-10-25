// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

/// The date of a chat-list row (spec chat-list, bipe-chat/bipe#592). The SDK latest event skips encrypted events that
/// this phone cannot decrypt, so a room whose recent events are all undecryptable has no latest event and the row had
/// no date. The time of an event is never encrypted: such a row takes the time of the newest event in the room's local
/// timeline instead. Never the current time, never a guess.
public enum BipeRowActivity {
    /// A message-like event (text, media, sticker, poll, deleted or undecryptable) or any other timeline event.
    public enum Kind: Sendable, Equatable {
        case message
        case other
    }

    /// One event of the room's local timeline: its origin_server_ts in milliseconds.
    public struct LocalEvent: Sendable, Equatable {
        public let timestampMs: UInt64
        public let kind: Kind

        public init(timestampMs: UInt64, kind: Kind) {
            self.timestampMs = timestampMs
            self.kind = kind
        }
    }

    /// Only a row without an SDK latest event reads the local timeline.
    public static func needsLocalTimeline(sdkLatestMs: UInt64?) -> Bool {
        sdkLatestMs == nil
    }

    /// The row time in milliseconds, or nil while nothing is known on this phone.
    /// 1. The SDK latest event. 2. The newest message-like local event, undecryptable ones included. 3. The newest local
    /// event of any kind. A time known earlier in this account session is kept when it is newer than 2 and 3.
    public static func resolve(sdkLatestMs: UInt64?, localEvents: [LocalEvent], knownMs: UInt64?) -> UInt64? {
        if let sdkLatestMs { return sdkLatestMs }
        let local = localEvents.filter { $0.kind == .message }.map(\.timestampMs).max()
            ?? localEvents.map(\.timestampMs).max()
        switch (local, knownMs) {
        case let (local?, known?): return max(local, known)
        case let (local, known): return local ?? known
        }
    }
}
