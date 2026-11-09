// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Contract `HandleMoveCode` (bipe ADR 0287): a one-time code that moves the @ of a personal account to a work account
/// on the same phone. The app passes it to the claim and never shows it.
public struct BipeHandleMoveCode: Decodable, Sendable, Equatable {
    public let code: String
    public let expiresAt: Date
}

public extension BipeAPIClient {
    private struct MoveClaimBody: Encodable { let code: String }

    /// `GET /v1/me/handle` with a Matrix access token, for another account on this phone.
    func myHandle(accessToken: String) async throws(BipeAPIError) -> BipeMyHandle {
        try await send(method: "GET", path: ["v1", "me", "handle"], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/me/handle/move`. The caller is a personal account with an @.
    func startHandleMove(accessToken: String) async throws(BipeAPIError) -> BipeHandleMoveCode {
        try await send(method: "POST", path: ["v1", "me", "handle", "move"], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/me/handle/move/claim`. The caller is a work account with no @; it takes the @ and its public feed.
    func claimHandleMove(code: String, accessToken: String) async throws(BipeAPIError) -> BipeMyHandle {
        try await send(method: "POST", path: ["v1", "me", "handle", "move", "claim"], body: MoveClaimBody(code: code), accessToken: accessToken)
    }
}
