// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

/// The inputs of BipeHomeScore for one room (spec chat-list, Mural inputs; ADR 0246). A pure mapping from the facts of the
/// row and the local timeline events. Nothing here reads the network or leaves the phone.
public enum BipeHomeInputs {
    /// The facts of a chat-list row that the score reads.
    public struct Room: Sendable, Equatable {
        public let id: String
        public let isDm: Bool
        /// GET /v1/accounts/{userId}/type of the other person says company (ADR 0132).
        public let isCompany: Bool
        public let sealMark: BipeSealMark?
        public let official: Bool
        public let favourite: Bool
        public let muted: Bool
        public let mentionCount: Int
        public let unreadCount: Int
        /// The row date. nil: unknown.
        public let lastActivityMs: UInt64?

        public init(id: String, isDm: Bool, isCompany: Bool, sealMark: BipeSealMark?, official: Bool, favourite: Bool,
                    muted: Bool, mentionCount: Int, unreadCount: Int, lastActivityMs: UInt64?) {
            self.id = id
            self.isDm = isDm
            self.isCompany = isCompany
            self.sealMark = sealMark
            self.official = official
            self.favourite = favourite
            self.muted = muted
            self.mentionCount = mentionCount
            self.unreadCount = unreadCount
            self.lastActivityMs = lastActivityMs
        }
    }

    /// The input of the score. Step 1 of ADR 0246: no trust warning and no model fields. No local events read yet gives
    /// lastFromOther false and messagesPerWeek 0.
    public static func chat(_ room: Room, events: [BipeRowActivity.LocalEvent], nowMs: UInt64) -> BipeHomeChat {
        BipeHomeChat(
            id: room.id,
            kind: !room.isDm ? .group : (room.isCompany ? .company : .person),
            // A suspended company seal counts as no seal.
            hasSeal: room.sealMark == .company,
            official: room.official,
            favourite: room.favourite,
            muted: room.muted,
            mentioned: room.mentionCount > 0,
            unreadCount: room.unreadCount,
            lastFromOther: lastFromOther(events),
            minutesSinceActivity: room.lastActivityMs.map { minutes(from: $0, to: nowMs) },
            messagesPerWeek: messagesPerWeek(events, nowMs: nowMs))
    }

    /// The count of message-like events from anyone in the window of the registry. Undecryptable events count.
    public static func messagesPerWeek(_ events: [BipeRowActivity.LocalEvent], nowMs: UInt64) -> Int {
        let window = UInt64(BipeHomeScore.messagesPerWeekWindowDays) * 86_400_000
        let start = nowMs > window ? nowMs - window : 0
        return events.filter { isMessageLike($0) && $0.timestampMs >= start }.count
    }

    /// The newest message-like event has another sender. False when there is none.
    public static func lastFromOther(_ events: [BipeRowActivity.LocalEvent]) -> Bool {
        guard let newest = events.filter(isMessageLike).max(by: { $0.timestampMs < $1.timestampMs }) else { return false }
        return !newest.isOwn
    }

    /// The preview texts of the newest messages, oldest first (spec chat-list, type MuralTile).
    public static func previews(_ events: [BipeRowActivity.LocalEvent], limit: Int) -> [String] {
        let messages = events.filter { isMessageLike($0) && $0.previewText != nil }
            .sorted { $0.timestampMs < $1.timestampMs }
        return messages.suffix(limit).compactMap(\.previewText)
    }

    /// Whole minutes, rounded down. A time in the future counts as now.
    private static func minutes(from startMs: UInt64, to nowMs: UInt64) -> Int {
        nowMs > startMs ? Int((nowMs - startMs) / 60_000) : 0
    }

    private static func isMessageLike(_ event: BipeRowActivity.LocalEvent) -> Bool {
        event.kind == .message && !event.isStateLine
    }
}
