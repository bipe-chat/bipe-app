// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// Sticker packs per MSC2545 (ADR 0033). Same rules as android/libraries/bipe stickers/StickerPacks.kt.

public enum StickerPackEventType {
    /// Global account data: the user's own pack.
    public static let userPack = "im.ponies.user_emotes"
    /// Room state: packs shared in a room. The state key names the pack.
    public static let roomPack = "im.ponies.room_emotes"
    /// Global account data: room packs the user enabled everywhere.
    public static let enabledRooms = "im.ponies.emote_rooms"
}

/// Type StickerItem of spec sticker-picker.
public struct Sticker: Identifiable, Equatable, Sendable {
    public let packId: String
    public let shortcode: String
    /// The mxc:// URI of the image.
    public let url: String
    public let body: String
    /// info.is_animated of the image; nil when the pack does not say (ADR 0205).
    public var isAnimated: Bool? = nil
    public let width: Int?
    public let height: Int?
    public let mimeType: String?
    public let size: Int?

    public var id: String { "\(packId)/\(shortcode)" }

    /// The JSON content of an m.sticker event.
    public func eventContentJSON() -> String {
        var info: [String: Any] = [:]
        if let width { info["w"] = width }
        if let height { info["h"] = height }
        if let mimeType { info["mimetype"] = mimeType }
        if let size { info["size"] = size }
        if let isAnimated { info["is_animated"] = isAnimated }
        let content: [String: Any] = ["body": body, "info": info, "url": url]
        let data = (try? JSONSerialization.data(withJSONObject: content, options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}

/// Type StickerPackView of spec sticker-picker.
public struct StickerPack: Identifiable, Equatable, Sendable {
    public enum Source: Equatable, Sendable {
        case user
        case room(roomId: String, stateKey: String)
    }

    public let source: Source
    public let displayName: String?
    public let stickers: [Sticker]

    public var id: String { Self.packId(source) }

    /// Parses an MSC2545 pack. Keeps images whose usage includes sticker, or that state no usage.
    /// nil when the content is not a pack or holds no stickers.
    public static func parse(_ json: Data, source: Source) -> StickerPack? {
        guard let content = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else { return nil }
        return parse(content, source: source)
    }

    public static func parse(_ content: [String: Any], source: Source) -> StickerPack? {
        guard let images = content["images"] as? [String: Any] else { return nil }
        let pack = content["pack"] as? [String: Any]
        let packUsage = pack?["usage"] as? [String]
        let packId = packId(source)
        let stickers: [Sticker] = images.keys.sorted().compactMap { shortcode in
            guard let image = images[shortcode] as? [String: Any],
                  let url = image["url"] as? String, url.hasPrefix("mxc://") else { return nil }
            let usage = image["usage"] as? [String] ?? packUsage
            if let usage, !usage.isEmpty, !usage.contains("sticker") { return nil }
            let info = image["info"] as? [String: Any]
            return Sticker(packId: packId,
                           shortcode: shortcode,
                           url: url,
                           body: image["body"] as? String ?? shortcode,
                           isAnimated: info?["is_animated"] as? Bool,
                           width: info?["w"] as? Int,
                           height: info?["h"] as? Int,
                           mimeType: info?["mimetype"] as? String,
                           size: info?["size"] as? Int)
        }
        guard !stickers.isEmpty else { return nil }
        return StickerPack(source: source, displayName: pack?["display_name"] as? String, stickers: stickers)
    }

    /// im.ponies.emote_rooms content: {"rooms": {roomId: {stateKey: {}}}}.
    public static func parseEnabledRooms(_ json: Data) -> [(roomId: String, stateKey: String)] {
        guard let content = try? JSONSerialization.jsonObject(with: json) as? [String: Any],
              let rooms = content["rooms"] as? [String: Any] else { return [] }
        return rooms.keys.sorted().flatMap { roomId in
            ((rooms[roomId] as? [String: Any])?.keys.sorted() ?? []).map { (roomId, $0) }
        }
    }

    private static func packId(_ source: Source) -> String {
        switch source {
        case .user: "user"
        case .room(let roomId, let stateKey): "\(roomId)/\(stateKey)"
        }
    }
}
