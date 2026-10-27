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
    /// A message-like event (text, media, sticker, poll, deleted or undecryptable, or a call) or any other timeline event.
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

    /// The row of a room. Only a local event with a preview can give the row its text (bipe-chat/bipe#592 follow-up: a
    /// call, a deleted message or a room change never blanks the text).
    /// 1. A message-like local event with a preview, newer than the SDK latest event: its time and its preview.
    /// 2. Else the SDK latest event. When it has no preview, the row keeps the newest local preview, else the known one.
    /// 3. Without an SDK latest event: the newest message-like local event with a preview, undecryptable ones included.
    ///    Else the newest local event of any kind (time only). A time known earlier in this account session is kept when
    ///    it is newer. Without a local preview the row keeps the known one: the local events are empty while a changed
    ///    room is read again.
    public static func resolve(sdk: SDKLatest?, localEvents: [LocalEvent], known: Row?) -> Row {
        let newestMessage = localEvents.filter { $0.kind == .message && $0.previewText != nil }
            .max { $0.timestampMs < $1.timestampMs }
        let text = newestMessage.map(row(from:)) ?? known
        if let sdk {
            if let newestMessage, newestMessage.timestampMs > sdk.timestampMs {
                return row(from: newestMessage)
            }
            if sdk.previewText == nil, let text, text.previewText != nil {
                return Row(timestampMs: sdk.timestampMs, previewText: text.previewText, latestIncomingMs: text.latestIncomingMs)
            }
            return Row(timestampMs: sdk.timestampMs, previewText: sdk.previewText,
                       latestIncomingMs: sdk.isIncoming && sdk.previewText != nil ? sdk.timestampMs : nil)
        }
        if let newestMessage {
            let message = row(from: newestMessage)
            let time = max(newestMessage.timestampMs, known?.timestampMs ?? 0)
            return Row(timestampMs: time, previewText: message.previewText, latestIncomingMs: message.latestIncomingMs)
        }
        let time = (localEvents.map(\.timestampMs) + [known?.timestampMs].compactMap { $0 }).max()
        return Row(timestampMs: time, previewText: known?.previewText, latestIncomingMs: known?.latestIncomingMs)
    }

    private static func row(from event: LocalEvent) -> Row {
        Row(timestampMs: event.timestampMs, previewText: event.previewText,
            latestIncomingMs: !event.isOwn && event.previewText != nil ? event.timestampMs : nil)
    }
}
