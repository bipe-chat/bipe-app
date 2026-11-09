// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Contract `MyFeed` (bipe ADR 0280 point 7c, bipe-chat/bipe#1260): the handle owner's public feed, which staff linked.
public struct BipeMyFeed: Decodable, Sendable, Equatable {
    public let handle: String
    public let roomId: String
    public let enabled: Bool
    public let url: URL
}

/// Contract `FeedRoom`: the feed a group posts to, for an admin of that group.
public struct BipeFeedRoom: Decodable, Sendable, Equatable {
    public let handle: String
    public let url: URL
}

/// Contract `FeedPost`: one post of a public feed.
public struct BipeFeedPost: Decodable, Sendable, Equatable {
    public let id: String
    public let text: String
    public let createdAt: Date
    public let url: URL
}

public extension BipeAPIClient {
    private struct EnabledBody: Encodable { let enabled: Bool }
    private struct TextBody: Encodable { let text: String }

    /// `GET /v1/me/feed`. `notFound` when staff never linked a feed to the caller's handle.
    func myFeed(accessToken: String) async throws(BipeAPIError) -> BipeMyFeed {
        try await send(method: "GET", path: ["v1", "me", "feed"], body: Empty?.none, accessToken: accessToken)
    }

    /// `PUT /v1/me/feed`. Off takes the page down and stops new posts.
    func setMyFeed(enabled: Bool, accessToken: String) async throws(BipeAPIError) -> BipeMyFeed {
        try await send(method: "PUT", path: ["v1", "me", "feed"], body: EnabledBody(enabled: enabled), accessToken: accessToken)
    }

    /// `GET /v1/me/feed/rooms/{roomId}`. `notFound` unless the room posts to a feed and the caller is an admin there.
    func feedRoom(roomId: String, accessToken: String) async throws(BipeAPIError) -> BipeFeedRoom {
        try await send(method: "GET", path: ["v1", "me", "feed", "rooms", roomId], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/me/feed/posts`: the plain copy of a group post.
    func publishFeedPost(text: String, accessToken: String) async throws(BipeAPIError) -> BipeFeedPost {
        try await send(method: "POST", path: ["v1", "me", "feed", "posts"], body: TextBody(text: text), accessToken: accessToken)
    }

    /// `DELETE /v1/me/feed/posts/{id}`.
    func deleteFeedPost(id: String, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "DELETE", path: ["v1", "me", "feed", "posts", id], body: Empty?.none, accessToken: accessToken)
    }
}

/// The mark of a group post that also went to the public feed: the top-level object `feedPostKey` of
/// registries/group-power-levels.json with the feed post's id and url. A forward drops it (BipeForwardMark).
public enum BipeFeedPostMark {
    /// The extra content JSON for Timeline.sendWithExtraContent: only the mark. Nil when it cannot be written.
    public static func extraContentJson(post: BipeFeedPost) -> String? {
        let extra: [String: Any] = [BipeGroupPowerLevels.feedPostKey: ["id": post.id, "url": post.url.absoluteString]]
        guard let data = try? JSONSerialization.data(withJSONObject: extra) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// The feed post id from event JSON (the whole event or its content). Nil without a well-formed mark.
    public static func postId(eventJson: String?) -> String? {
        guard let data = eventJson?.data(using: .utf8),
              let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let content = (object["content"] as? [String: Any]) ?? object
        guard let mark = content[BipeGroupPowerLevels.feedPostKey] as? [String: Any],
              let id = mark["id"] as? String,
              !id.isEmpty, id.count <= 40, id.allSatisfy({ $0.isASCII && ($0.isLowercase || $0.isNumber) }) else { return nil }
        return id
    }
}
