// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// A pack in a room (ADR 0205, bipe-chat/bipe#842): the target of a pack link and of stickerPackField on a sent
/// sticker. Same rules as android/libraries/bipe stickers/StickerPackShare.kt.
public struct StickerPackRef: Hashable, Sendable, Identifiable {
    public let roomId: String
    /// Empty for a pack link: a pack room holds its pack at state key "".
    public let stateKey: String

    public var id: String { "\(roomId)/\(stateKey)" }

    public init(roomId: String, stateKey: String) {
        self.roomId = roomId
        self.stateKey = stateKey
    }

    /// Room IDs have the form !opaque:server; neither part holds a slash.
    static func isRoomId(_ value: String) -> Bool {
        value.hasPrefix("!") && value.contains(":") && !value.contains("/") && !value.contains(where: \.isWhitespace)
    }

    /// stickerPackLinkBase plus the room ID, URL-encoded (spec sticker-picker sharePack).
    public var link: String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return BipeRegistries.stickerPackLinkBase + (roomId.addingPercentEncoding(withAllowedCharacters: allowed) ?? roomId)
    }

    /// The pack of a pack link (https://bipe.chat/s/<room ID>, also www.), or nil when the URL is not one.
    public static func fromLink(_ url: String) -> StickerPackRef? {
        guard let base = URLComponents(string: BipeRegistries.stickerPackLinkBase), let components = URLComponents(string: url),
              components.scheme == "https", let host = components.host?.lowercased(), let baseHost = base.host,
              host == baseHost || host == "www.\(baseHost)",
              components.percentEncodedPath.hasPrefix(base.percentEncodedPath) else { return nil }
        let encoded = String(components.percentEncodedPath.dropFirst(base.percentEncodedPath.count))
        guard let roomId = encoded.removingPercentEncoding, isRoomId(roomId) else { return nil }
        return StickerPackRef(roomId: roomId, stateKey: "")
    }

    /// stickerPackField of an event: content[stickerPackField] = { room_id, state_key }. nil when absent or malformed.
    public static func read(eventJson: String) -> StickerPackRef? {
        guard let event = try? JSONSerialization.jsonObject(with: Data(eventJson.utf8)) as? [String: Any] else { return nil }
        let content = event["content"] as? [String: Any] ?? event
        guard let field = content[BipeRegistries.stickerPackField] as? [String: Any],
              let roomId = field["room_id"] as? String, isRoomId(roomId),
              let stateKey = field["state_key"] as? String else { return nil }
        return StickerPackRef(roomId: roomId, stateKey: stateKey)
    }
}

extension StickerPack {
    /// The room pack; nil for the user's own pack.
    public var ref: StickerPackRef? {
        guard case .room(let roomId, let stateKey) = source else { return nil }
        return StickerPackRef(roomId: roomId, stateKey: stateKey)
    }
}

extension Sticker {
    /// The room pack this sticker comes from; nil for the user's own pack. packId is "user" or "<room ID>/<state key>".
    public var packRef: StickerPackRef? {
        guard let slash = packId.firstIndex(of: "/") else { return nil }
        let roomId = String(packId[..<slash])
        guard StickerPackRef.isRoomId(roomId) else { return nil }
        return StickerPackRef(roomId: roomId, stateKey: String(packId[packId.index(after: slash)...]))
    }

    /// The m.sticker content to send (spec sticker-picker send): eventContentJSON, plus stickerPackField with
    /// { room_id, state_key } when the sticker is from a room pack.
    public func sendContentJSON() -> String {
        let json = eventContentJSON()
        guard let ref = packRef, var content = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { return json }
        content[BipeRegistries.stickerPackField] = ["room_id": ref.roomId, "state_key": ref.stateKey]
        guard let data = try? JSONSerialization.data(withJSONObject: content, options: [.sortedKeys]) else { return json }
        return String(decoding: data, as: UTF8.self)
    }
}

/// What sticker-pack shows of a pack room, read from its state.
public struct StickerPackPreview: Equatable, Sendable {
    public let pack: StickerPack
    /// The sender of m.room.create (spec sticker-pack notes).
    public let creatorId: String?
    /// m.room.name, for a pack without a display name.
    public let roomName: String?

    /// Reads the pack at ref.stateKey, the creator and the room name from GET /rooms/{roomId}/state.
    public static func parse(state json: Data, ref: StickerPackRef) -> StickerPackPreview? {
        guard let events = try? JSONSerialization.jsonObject(with: json) as? [[String: Any]] else { return nil }
        func event(_ type: String, _ stateKey: String = "") -> [String: Any]? {
            events.first { $0["type"] as? String == type && $0["state_key"] as? String == stateKey }
        }
        guard let content = event(StickerPackEventType.roomPack, ref.stateKey)?["content"] as? [String: Any],
              let pack = StickerPack.parse(content, source: .room(roomId: ref.roomId, stateKey: ref.stateKey)) else { return nil }
        return StickerPackPreview(pack: pack,
                                  creatorId: event("m.room.create")?["sender"] as? String,
                                  roomName: (event("m.room.name")?["content"] as? [String: Any])?["name"] as? String)
    }

    /// Whether userId may write the pack state: the room creator, or a power level at least the one the pack event
    /// needs (events, else state_default, else 50; users, else users_default, else 0).
    public static func canWrite(state json: Data, roomId: String, userId: String) -> Bool {
        guard let events = try? JSONSerialization.jsonObject(with: json) as? [[String: Any]] else { return false }
        if events.contains(where: { $0["type"] as? String == "m.room.create" && $0["sender"] as? String == userId }) { return true }
        guard let levels = events.first(where: { $0["type"] as? String == "m.room.power_levels" })?["content"] as? [String: Any] else {
            return false
        }
        let needed = (levels["events"] as? [String: Any])?[StickerPackEventType.roomPack] as? Int ?? levels["state_default"] as? Int ?? 50
        let own = (levels["users"] as? [String: Any])?[userId] as? Int ?? levels["users_default"] as? Int ?? 0
        return own >= needed
    }
}

/// Content of a new pack room (spec sticker-picker notes, Pack room): public join rule, world_readable history,
/// no encryption (ADR 0179), only the creator writes state and messages, the pack copy at state key "".
public enum StickerPackRoom {
    /// The POST /createRoom body. userPack is the content of im.ponies.user_emotes; the copy gets displayName
    /// when the pack has none.
    public static func createBody(userPack: [String: Any], displayName: String) -> [String: Any] {
        var copy = userPack
        var pack = copy["pack"] as? [String: Any] ?? [:]
        let name = (pack["display_name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? displayName
        pack["display_name"] = name
        copy["pack"] = pack
        return [
            "preset": "public_chat",
            "visibility": "private",
            "name": name,
            "initial_state": [
                ["type": "m.room.history_visibility", "state_key": "", "content": ["history_visibility": "world_readable"]],
                ["type": StickerPackEventType.roomPack, "state_key": "", "content": copy],
            ],
            "power_level_content_override": ["events_default": 100, "state_default": 100, "invite": 100],
        ]
    }

    /// im.ponies.emote_rooms content with ref added, keeping every other field.
    public static func enabling(_ ref: StickerPackRef, in content: [String: Any]) -> [String: Any] {
        var content = content
        var rooms = content["rooms"] as? [String: Any] ?? [:]
        var keys = rooms[ref.roomId] as? [String: Any] ?? [:]
        if keys[ref.stateKey] == nil { keys[ref.stateKey] = [String: Any]() }
        rooms[ref.roomId] = keys
        content["rooms"] = rooms
        return content
    }
}
