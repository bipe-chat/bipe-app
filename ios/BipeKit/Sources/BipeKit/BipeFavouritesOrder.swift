// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Favourites order (spec chat-list; bipe-shared registries/chat-favourites.json). Global account data
// BipeRegistries.chatFavouritesOrderEventType with content {"rooms": [roomId, ...]}, first room first.
// Same rules as android/libraries/bipe shared/BipeFavouritesOrder.kt.
public enum BipeFavouritesOrder {
    public static let eventType = BipeRegistries.chatFavouritesOrderEventType

    /// The room IDs in the account data content, in order, without duplicates. Empty on nil or malformed content.
    public static func parse(_ json: String?) -> [String] {
        guard let data = json?.data(using: .utf8),
              let content = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rooms = content["rooms"] as? [Any] else { return [] }
        var seen = Set<String>()
        return rooms.compactMap { $0 as? String }.filter { seen.insert($0).inserted }
    }

    /// The account data content for an order. Keeps the first chatFavouritesOrderMax room IDs.
    public static func content(_ rooms: [String]) -> String {
        let limited = Array(rooms.prefix(BipeRegistries.chatFavouritesOrderMax))
        let data = (try? JSONSerialization.data(withJSONObject: ["rooms": limited], options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }

    /// Favourites in display order: the room IDs in the order first, in order; then the others by name
    /// (case-insensitive), then room ID.
    public static func sorted<Item>(_ items: [Item], order: [String], roomId: (Item) -> String, name: (Item) -> String) -> [Item] {
        var position: [String: Int] = [:]
        for (index, id) in order.enumerated() where position[id] == nil { position[id] = index }
        return items.sorted { a, b in
            let (idA, idB) = (roomId(a), roomId(b))
            switch (position[idA], position[idB]) {
            case let (pa?, pb?): return pa < pb
            case (.some, .none): return true
            case (.none, .some): return false
            case (.none, .none):
                let byName = name(a).localizedCaseInsensitiveCompare(name(b))
                return byName == .orderedSame ? idA < idB : byName == .orderedAscending
            }
        }
    }

    /// A new favourite goes last.
    public static func adding(_ roomId: String, to order: [String]) -> [String] {
        order.filter { $0 != roomId } + [roomId]
    }

    /// A removed favourite leaves the order.
    public static func removing(_ roomId: String, from order: [String]) -> [String] {
        order.filter { $0 != roomId }
    }

    /// The order after the user moves `roomId` to `index` among `shown`, the favourites of one scope in display order.
    /// Shown rooms not yet in the order join it at the end first. The rooms of the other scope keep their positions.
    public static func moving(_ roomId: String, to index: Int, shown: [String], order: [String]) -> [String] {
        guard let from = shown.firstIndex(of: roomId) else { return order }
        var moved = shown
        moved.remove(at: from)
        moved.insert(roomId, at: min(max(index, 0), moved.count))
        let full = order + shown.filter { !order.contains($0) }
        let inScope = Set(shown)
        var next = moved.makeIterator()
        return full.map { inScope.contains($0) ? (next.next() ?? $0) : $0 }
    }
}
