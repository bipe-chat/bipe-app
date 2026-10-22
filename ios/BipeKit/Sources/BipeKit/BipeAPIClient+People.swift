// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The Bipe API call of spec contacts that BipeAPIClient does not have yet. Contract: bipe-shared/api/openapi.yaml.

import Foundation

extension BipeAPIClient {
    /// `GET /v1/me/invite-connections`. The people the user invited who joined, and whoever invited the user (ADR 0034).
    public func inviteConnections(accessToken: String) async throws(BipeAPIError) -> Set<String> {
        let response: InviteConnections = try await send(method: "GET", path: ["v1", "me", "invite-connections"], body: Empty?.none, accessToken: accessToken)
        return Set(response.userIds)
    }

    private struct InviteConnections: Decodable {
        let userIds: [String]
    }
}
