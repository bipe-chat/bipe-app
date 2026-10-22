// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Cleared DMs ("Apagar conversa", bipe#464): the global account data event `chat.bipe.cleared_chats`,
/// content `{ "rooms": { "<room_id>": { "ts": <origin_server_ts in ms> } } }`.
/// Port of `clearedChat*` in bipe-shared `tools/lib/registries.mjs`; `test-vectors/cleared-chats.json` holds the cases.
public struct ClearedChats: Equatable, Sendable {
    public static let eventType = BipeRegistries.clearedChatsEventType
    public static let empty = ClearedChats(markers: [:])
    
    /// The valid markers by room ID. A malformed event or entry counts as not cleared.
    public let markers: [String: UInt64]
    
    public init(markers: [String: UInt64]) {
        self.markers = markers
    }
    
    /// Reads a content object from `JSONSerialization`. Anything other than an object with a `rooms` object is empty.
    public init(content: Any?) {
        var markers: [String: UInt64] = [:]
        for (roomID, entry) in Self.rooms(in: content) ?? [:] {
            if let ts = Self.timestamp(of: entry) {
                markers[roomID] = ts
            }
        }
        self.init(markers: markers)
    }
    
    /// Reads the account data as the SDK returns it: a JSON string, `nil` when the event does not exist.
    public init(json: String?) {
        self.init(content: json.flatMap(Self.parse))
    }
    
    public func marker(for roomID: String) -> UInt64? {
        markers[roomID]
    }
    
    /// True when the chat list hides the room: it has a marker and no event after it. `latestTs` is `nil` when the room has no latest event.
    public static func isHidden(marker: UInt64?, latestTs: UInt64?) -> Bool {
        guard let marker else { return false }
        guard let latestTs else { return true }
        return latestTs <= marker
    }
    
    /// True when the timeline and search keep an event with this `origin_server_ts`.
    public static func keepsEvent(marker: UInt64?, eventTs: UInt64) -> Bool {
        guard let marker else { return true }
        return eventTs > marker
    }
    
    /// The content to write when the user clears `roomID` at `ts`. Keeps every other key and entry as it is. The marker never moves back.
    public static func content(clearing content: Any?, roomID: String, at ts: UInt64) -> [String: Any] {
        var base = content as? [String: Any] ?? [:]
        var rooms = Self.rooms(in: base) ?? [:]
        let old = rooms[roomID].flatMap(Self.timestamp(of:))
        rooms[roomID] = ["ts": old.map { max($0, ts) } ?? ts]
        base["rooms"] = rooms
        return base
    }
    
    /// `content(clearing:roomID:at:)` on the JSON string the SDK returns. `nil` only when the result cannot be encoded.
    public static func json(clearing json: String?, roomID: String, at ts: UInt64) -> String? {
        let content = Self.content(clearing: json.flatMap(Self.parse), roomID: roomID, at: ts)
        guard let data = try? JSONSerialization.data(withJSONObject: content) else { return nil }
        return String(data: data, encoding: .utf8)
    }
    
    // MARK: - Private
    
    /// `Number.MAX_SAFE_INTEGER`: the reference implementation accepts safe integers only.
    private static let maxSafeInteger: Double = 9_007_199_254_740_991
    
    private static func parse(_ json: String) -> Any? {
        try? JSONSerialization.jsonObject(with: Data(json.utf8), options: [.fragmentsAllowed])
    }
    
    private static func rooms(in content: Any?) -> [String: Any]? {
        (content as? [String: Any])?["rooms"] as? [String: Any]
    }
    
    private static func timestamp(of entry: Any) -> UInt64? {
        // JSONSerialization reads true and false as NSNumber too.
        guard let number = (entry as? [String: Any])?["ts"] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            return nil
        }
        let value = number.doubleValue
        guard value >= 0, value <= maxSafeInteger, value.rounded(.towardZero) == value else { return nil }
        return UInt64(value)
    }
}
