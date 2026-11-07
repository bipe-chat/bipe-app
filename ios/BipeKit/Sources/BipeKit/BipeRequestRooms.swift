// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Chats accepted from chat-requests (spec timeline-media fromStranger; bipe-shared registries/request-rooms.json).
// Global account data BipeRegistries.requestRoomsEventType with content {"rooms": [roomId, ...]}, newest last.
// Same rules as android/libraries/bipe shared/BipeRequestRooms.kt.
public enum BipeRequestRooms {
    public static let eventType = BipeRegistries.requestRoomsEventType

    /// The room IDs in the account data content, without duplicates. Empty on nil or malformed content.
    public static func parse(_ json: String?) -> [String] {
        guard let data = json?.data(using: .utf8),
              let content = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rooms = content["rooms"] as? [Any] else { return [] }
        var seen = Set<String>()
        return rooms.compactMap { $0 as? String }.filter { seen.insert($0).inserted }
    }

    /// The list with the new room IDs last. A room ID already in the list keeps its place. Keeps the newest requestRoomsMax.
    public static func merged(_ rooms: [String], adding: [String]) -> [String] {
        var seen = Set<String>()
        return Array((rooms + adding).filter { seen.insert($0).inserted }.suffix(BipeRegistries.requestRoomsMax))
    }

    /// The account data content for a list.
    public static func content(_ rooms: [String]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: ["rooms": rooms], options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
