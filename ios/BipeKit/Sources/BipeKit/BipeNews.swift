// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Novidades do Bipe (ADR 0117, bipe-chat/bipe#387): the answer of GET, PUT and DELETE /v1/me/news.
public struct BipeNewsState: Decodable, Equatable, Sendable {
    public let subscribed: Bool
    /// The user's news room, only when subscribed.
    public let roomId: String?

    public init(subscribed: Bool, roomId: String?) {
        self.subscribed = subscribed
        self.roomId = roomId
    }
}

public extension BipeAPIClient {
    /// `GET /v1/me/news`.
    func newsState(accessToken: String) async throws(BipeAPIError) -> BipeNewsState {
        try await send(method: "GET", path: ["v1", "me", "news"], body: Empty?.none, accessToken: accessToken)
    }

    /// `PUT /v1/me/news` (on: the kept or a new room) or `DELETE /v1/me/news` (off).
    func setNews(_ isOn: Bool, accessToken: String) async throws(BipeAPIError) -> BipeNewsState {
        try await send(method: isOn ? "PUT" : "DELETE", path: ["v1", "me", "news"], body: Empty?.none, accessToken: accessToken)
    }
}

/// Novidades do Bipe is a named room, not a DM (bipe-chat/bipe#809, bipe-shared spec chat-list notes). bipe-api made
/// news rooms with `is_direct` before, so the user's `m.direct` lists them. The app takes the news room out once per
/// account. The same cases are in `BipeNewsDirectCleanupTest.kt`.
public enum BipeNewsDirectCleanup {
    /// The global account data event that lists the DM rooms of the account by the other user (Matrix spec, m.direct).
    public static let directEventType = "m.direct"

    /// The `m.direct` content without `roomId`, or `nil` when `roomId` is not in it (nothing to write). Every other
    /// entry stays as it is. A user whose list becomes empty goes.
    public static func json(removing roomId: String, from json: String?) -> String? {
        guard let json,
              let content = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any] else { return nil }
        var changed = false
        var out: [String: Any] = [:]
        for (user, value) in content {
            guard let rooms = value as? [Any] else {
                out[user] = value
                continue
            }
            let kept = rooms.filter { ($0 as? String) != roomId }
            if kept.count != rooms.count {
                changed = true
                if kept.isEmpty { continue }
            }
            out[user] = kept
        }
        guard changed, let data = try? JSONSerialization.data(withJSONObject: out, options: [.sortedKeys]) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
