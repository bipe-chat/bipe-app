// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Reads and writes MSC2545 sticker packs over the Matrix client-server API (ADR 0033): the SDK cannot
/// read custom room state under sliding sync. Requests carry the session's access token; nothing logs them.
public struct StickerPackClient: Sendable {
    public enum ClientError: Error {
        case unauthorized
        case http(Int)
        case noContentURI
    }

    private let homeserverURL: URL
    private let userId: String
    /// The current access token; read again after an HTTP 401.
    private let accessToken: @Sendable () -> String?

    public init(homeserverURL: URL, userId: String, accessToken: @escaping @Sendable () -> String?) {
        self.homeserverURL = homeserverURL
        self.userId = userId
        self.accessToken = accessToken
    }

    /// The user's own pack, the packs of roomId, then the room packs the user enabled everywhere.
    public func packs(roomId: String) async throws -> [StickerPack] {
        try await withTokenRetry { token in
            var packs: [StickerPack] = []
            if let data = try await get(["user", userId, "account_data", StickerPackEventType.userPack], token: token),
               let pack = StickerPack.parse(data, source: .user) {
                packs.append(pack)
            }
            packs += try await roomPacks(roomId, token: token)
            if let data = try await get(["user", userId, "account_data", StickerPackEventType.enabledRooms], token: token) {
                for (enabledRoomId, stateKey) in StickerPack.parseEnabledRooms(data) where enabledRoomId != roomId {
                    // A pack in a room the user left is unreadable. Skip it, keep the others.
                    if let data = try? await get(["rooms", enabledRoomId, "state", StickerPackEventType.roomPack, stateKey], token: token),
                       let pack = StickerPack.parse(data, source: .room(roomId: enabledRoomId, stateKey: stateKey)) {
                        packs.append(pack)
                    }
                }
            }
            return packs
        }
    }

    /// Uploads the candidates that are not yet in the user's pack and writes the pack back, keeping every
    /// other field. Returns how many were added. A failed upload keeps what was added and then throws.
    public func addToUserPack(_ candidates: [StickerImportCandidate]) async throws -> Int {
        try await withTokenRetry { token in
            let path = ["user", userId, "account_data", StickerPackEventType.userPack]
            var content: [String: Any] = [:]
            if let data = try await get(path, token: token), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                content = object
            }
            var images = content["images"] as? [String: Any] ?? [:]
            var added = 0
            var uploadError: Error?
            var seen = Set<String>()
            for candidate in candidates {
                guard images[candidate.shortcode] == nil, !seen.contains(candidate.shortcode) else { continue }
                seen.insert(candidate.shortcode)
                do {
                    let ext = candidate.mimeType == "image/png" ? "png" : "webp"
                    let url = try await upload(candidate.data, mimeType: candidate.mimeType, fileName: "\(candidate.shortcode).\(ext)", token: token)
                    images[candidate.shortcode] = [
                        "url": url,
                        "body": "Sticker",
                        "usage": ["sticker"],
                        "info": candidate.info,
                    ] as [String: Any]
                    added += 1
                } catch {
                    uploadError = error
                    break
                }
            }
            if added > 0 {
                content["images"] = images
                let body = try JSONSerialization.data(withJSONObject: content)
                _ = try await perform("PUT", clientURL(path), token: token, body: body, contentType: "application/json")
            }
            if let uploadError { throw uploadError }
            return added
        }
    }

    /// A sticker list in global account data (StickerShortlist): recents or favourites. Empty when unset.
    public func shortlist(_ eventType: String) async throws -> [StickerRef] {
        try await withTokenRetry { token in
            try await get(["user", userId, "account_data", eventType], token: token).map(StickerShortlist.parse) ?? []
        }
    }

    /// Writes the whole list, so a later write also carries changes from a failed one.
    public func setShortlist(_ eventType: String, _ refs: [StickerRef]) async throws {
        try await withTokenRetry { token in
            _ = try await perform("PUT", clientURL(["user", userId, "account_data", eventType]), token: token,
                                  body: StickerShortlist.content(refs), contentType: "application/json")
        }
    }

    /// Spec sticker-pack appear: the pack at ref, the room creator and the room name, from the room state. A public
    /// world_readable room is read without joining. nil when the room has no pack at ref.stateKey.
    public func preview(_ ref: StickerPackRef) async throws -> StickerPackPreview? {
        try await withTokenRetry { token in
            try await get(["rooms", ref.roomId, "state"], token: token).flatMap { StickerPackPreview.parse(state: $0, ref: ref) }
        }
    }

    /// Whether ref is in im.ponies.emote_rooms (spec sticker-pack, phase added).
    public func isEnabled(_ ref: StickerPackRef) async throws -> Bool {
        try await withTokenRetry { token in
            guard let data = try await get(["user", userId, "account_data", StickerPackEventType.enabledRooms], token: token) else { return false }
            return StickerPack.parseEnabledRooms(data).contains { $0.roomId == ref.roomId && $0.stateKey == ref.stateKey }
        }
    }

    /// Spec sticker-pack add: puts ref in im.ponies.emote_rooms with a read-merge-write on the server copy.
    /// The caller joins the room first.
    public func enable(_ ref: StickerPackRef) async throws {
        try await withTokenRetry { token in
            let path = ["user", userId, "account_data", StickerPackEventType.enabledRooms]
            var content: [String: Any] = [:]
            if let data = try await get(path, token: token), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                content = object
            }
            let body = try JSONSerialization.data(withJSONObject: StickerPackRoom.enabling(ref, in: content))
            _ = try await perform("PUT", clientURL(path), token: token, body: body, contentType: "application/json")
        }
    }

    /// StickerPackView.canShare of a room pack: this user may write the pack state. false when the state is unreadable.
    public func canWritePack(roomId: String) async -> Bool {
        guard let state = try? await withTokenRetry({ token in try await get(["rooms", roomId, "state"], token: token) }) else { return false }
        return StickerPackPreview.canWrite(state: state, roomId: roomId, userId: userId)
    }

    /// Spec sticker-picker sharePack for the user's own pack: a new pack room with a copy of the pack (StickerPackRoom).
    /// Later edits of the user's pack do not change the copy.
    public func createPackRoom(displayName: String) async throws -> StickerPackRef {
        try await withTokenRetry { token in
            guard let data = try await get(["user", userId, "account_data", StickerPackEventType.userPack], token: token),
                  let pack = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ClientError.http(404) }
            let body = try JSONSerialization.data(withJSONObject: StickerPackRoom.createBody(userPack: pack, displayName: displayName))
            guard let response = try await perform("POST", clientURL(["createRoom"]), token: token, body: body, contentType: "application/json"),
                  let object = try? JSONSerialization.jsonObject(with: response) as? [String: Any],
                  let roomId = object["room_id"] as? String else { throw ClientError.http(404) }
            return StickerPackRef(roomId: roomId, stateKey: "")
        }
    }

    private func roomPacks(_ roomId: String, token: String) async throws -> [StickerPack] {
        guard let data = try await get(["rooms", roomId, "state"], token: token),
              let events = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return events.compactMap { event -> StickerPack? in
            guard event["type"] as? String == StickerPackEventType.roomPack,
                  let stateKey = event["state_key"] as? String,
                  let content = event["content"] as? [String: Any] else { return nil }
            return StickerPack.parse(content, source: .room(roomId: roomId, stateKey: stateKey))
        }
        .sorted { $0.id < $1.id }
    }

    private func withTokenRetry<T>(_ request: (String) async throws -> T) async throws -> T {
        guard let token = accessToken() else { throw ClientError.unauthorized }
        do {
            return try await request(token)
        } catch ClientError.unauthorized {
            guard let fresh = accessToken() else { throw ClientError.unauthorized }
            return try await request(fresh)
        }
    }

    private func get(_ path: [String], token: String) async throws -> Data? {
        try await perform("GET", clientURL(path), token: token, body: nil, contentType: nil)
    }

    /// POST /_matrix/media/v3/upload. Stickers are stored in clear (spec sticker-import notes).
    private func upload(_ data: Data, mimeType: String, fileName: String, token: String) async throws -> String {
        var components = URLComponents(url: homeserverURL.appendingPathComponent("_matrix/media/v3/upload"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "filename", value: fileName)]
        guard let url = components?.url,
              let body = try await perform("POST", url, token: token, body: data, contentType: mimeType),
              let object = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let uri = object["content_uri"] as? String, uri.hasPrefix("mxc://") else { throw ClientError.noContentURI }
        return uri
    }

    private func clientURL(_ path: [String]) -> URL {
        var url = homeserverURL.appendingPathComponent("_matrix/client/v3")
        for segment in path {
            url.appendPathComponent(segment)
        }
        return url
    }

    /// The body on 200, nil on 404.
    private func perform(_ method: String, _ url: URL, token: String, body: Data?, contentType: String?) async throws -> Data? {
        var request = URLRequest(url: url, timeoutInterval: 30)
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        request.httpBody = body
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200: return data
        case 404: return nil
        case 401: throw ClientError.unauthorized
        default: throw ClientError.http(status)
        }
    }
}
