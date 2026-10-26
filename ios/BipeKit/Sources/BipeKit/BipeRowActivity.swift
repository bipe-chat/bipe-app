// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

/// The date and text of a chat-list row (spec chat-list, bipe-chat/bipe#592). The SDK picks a room's latest event once,
/// when the event arrives, and skips encrypted events that this phone cannot decrypt at that moment. A message that
/// arrives before its room key is never picked, and nothing picks it after the timeline decrypts it, so the row keeps an
/// older event (the own join, for example) or has none. Each row also reads its local timeline: a newer message-like
/// event there gives the row its time and its preview. The time of an event is never encrypted. Never the current time,
/// never a guess.
public enum BipeRowActivity {
    /// A message-like event (text, media, sticker, poll, deleted or undecryptable) or any other timeline event.
    public enum Kind: Sendable, Equatable {
        case message
        case other
    }

    /// One event of the room's local timeline: its origin_server_ts in milliseconds, its chat-list preview (nil when the
    /// row shows no text for it) and whether this account sent it.
    public struct LocalEvent: Sendable, Equatable {
        public let timestampMs: UInt64
        public let kind: Kind
        public let previewText: String?
        public let isOwn: Bool

        public init(timestampMs: UInt64, kind: Kind, previewText: String? = nil, isOwn: Bool = false) {
            self.timestampMs = timestampMs
            self.kind = kind
            self.previewText = previewText
            self.isOwn = isOwn
        }
    }

    /// The SDK latest event of a row: its time, its preview and whether someone else sent it.
    public struct SDKLatest: Sendable, Equatable {
        public let timestampMs: UInt64
        public let previewText: String?
        public let isIncoming: Bool

        public init(timestampMs: UInt64, previewText: String?, isIncoming: Bool) {
            self.timestampMs = timestampMs
            self.previewText = previewText
            self.isIncoming = isIncoming
        }
    }

    /// What a row shows: its time and text, and the time of its newest incoming message (for the archive).
    public struct Row: Sendable, Equatable {
        public let timestampMs: UInt64?
        public let previewText: String?
        public let latestIncomingMs: UInt64?

        public init(timestampMs: UInt64?, previewText: String?, latestIncomingMs: UInt64?) {
            self.timestampMs = timestampMs
            self.previewText = previewText
            self.latestIncomingMs = latestIncomingMs
        }
    }

    /// The row of a room.
    /// 1. A message-like local event newer than the SDK latest event: its time and its preview.
    /// 2. Else the SDK latest event.
    /// 3. Without an SDK latest event: the newest message-like local event, undecryptable ones included, else the newest
    ///    local event of any kind (time only). A time known earlier in this account session is kept when it is newer.
    public static func resolve(sdk: SDKLatest?, localEvents: [LocalEvent], knownMs: UInt64?) -> Row {
        let newestMessage = localEvents.filter { $0.kind == .message }.max { $0.timestampMs < $1.timestampMs }
        if let sdk {
            if let newestMessage, newestMessage.timestampMs > sdk.timestampMs {
                return row(from: newestMessage)
            }
            return Row(timestampMs: sdk.timestampMs, previewText: sdk.previewText,
                       latestIncomingMs: sdk.isIncoming && sdk.previewText != nil ? sdk.timestampMs : nil)
        }
        if let newestMessage {
            if let knownMs, knownMs > newestMessage.timestampMs {
                return Row(timestampMs: knownMs, previewText: nil, latestIncomingMs: nil)
            }
            return row(from: newestMessage)
        }
        let local = localEvents.map(\.timestampMs).max()
        let time: UInt64? = switch (local, knownMs) {
        case let (local?, known?): max(local, known)
        case let (local, known): local ?? known
        }
        return Row(timestampMs: time, previewText: nil, latestIncomingMs: nil)
    }

    private static func row(from event: LocalEvent) -> Row {
        Row(timestampMs: event.timestampMs, previewText: event.previewText,
            latestIncomingMs: !event.isOwn && event.previewText != nil ? event.timestampMs : nil)
    }
}
