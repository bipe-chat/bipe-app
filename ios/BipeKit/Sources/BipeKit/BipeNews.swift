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
