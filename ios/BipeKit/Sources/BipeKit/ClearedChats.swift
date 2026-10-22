// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Cleared DMs, "Apagar conversa" (bipe-chat/bipe#464, ADR 0141). Rules: bipe-shared registries/cleared-chats.json and
/// the reference functions clearedChat* in tools/lib/registries.mjs; cases in test-vectors/cleared-chats.json.
/// The content of the global account data event is { "rooms": { "<room_id>": { "ts": <ms> } } }.
public enum ClearedChats {
    public static let eventType = "chat.bipe.cleared_chats"

    /// The parsed content of the event, or nil when there is none or it is not an object.
    public static func parse(_ json: String?) -> [String: Any]? {
        guard let data = json?.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// clearedChatMarker: the marker (ms) of a room, or nil. A malformed event or entry counts as not cleared.
    public static func marker(content: [String: Any]?, roomId: String) -> UInt64? {
        guard let rooms = content?["rooms"] as? [String: Any] else { return nil }
        return ts(rooms[roomId])
    }

    /// Every valid marker of the content, by room ID.
    public static func markers(content: [String: Any]?) -> [String: UInt64] {
        guard let rooms = content?["rooms"] as? [String: Any] else { return [:] }
        return rooms.compactMapValues(ts)
    }

    /// clearedChatHidden: the chat list hides the room while it has a marker and no event after it.
    /// latestTs is nil when the room has no latest event.
    public static func hidden(marker: UInt64?, latestTs: UInt64?) -> Bool {
        guard let marker else { return false }
        guard let latestTs else { return true }
        return latestTs <= marker
    }

    /// clearedChatKeepsEvent: the timeline and search keep an event with this origin_server_ts.
    public static func keepsEvent(marker: UInt64?, eventTs: UInt64) -> Bool {
        guard let marker else { return true }
        return eventTs > marker
    }

    /// clearedChatsWithClear: the content to write when the user clears roomId at ts. Keeps every other key and
    /// entry as it is. The marker never moves back.
    public static func withClear(content: [String: Any]?, roomId: String, ts newTs: UInt64) -> [String: Any] {
        var base = content ?? [:]
        var rooms = base["rooms"] as? [String: Any] ?? [:]
        let old = ts(rooms[roomId])
        rooms[roomId] = ["ts": max(old ?? 0, newTs)]
        base["rooms"] = rooms
        return base
    }

    /// A valid entry is an object whose ts is a whole number of 0 or more, up to 2^53 - 1.
    private static func ts(_ entry: Any?) -> UInt64? {
        guard let entry = entry as? [String: Any], let number = entry["ts"] as? NSNumber else { return nil }
        // JSON true and false also arrive as NSNumber.
        guard CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let value = number.doubleValue
        guard value >= 0, value <= 9_007_199_254_740_991, value.rounded() == value else { return nil }
        return UInt64(value)
    }
}
