// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Home-screen API calls from bipe-shared/api/openapi.yaml. Account shape is not a verification claim (ADR 0132).
extension BipeAPIClient {
    public func avatarAccountIsCompany(userID: String) async throws(BipeAPIError) -> Bool {
        let result: HomeAccountType = try await send(method: "GET", path: ["v1", "accounts", userID, "type"], body: Int?.none)
        return result.accountType == "company"
    }

    public func homeInviteQuota(accessToken: String) async throws(BipeAPIError) -> BipeHomeInviteQuota {
        try await send(method: "GET", path: ["v1", "me", "invites"], body: Int?.none, accessToken: accessToken)
    }

    public func homeCreateInvite(accessToken: String) async throws(BipeAPIError) -> BipeHomeInvite {
        try await send(method: "POST", path: ["v1", "me", "invites"], body: Int?.none, accessToken: accessToken)
    }
}

private struct HomeAccountType: Decodable {
    let accountType: String
}

public struct BipeHomeInviteQuota: Decodable, Sendable {
    public let available: Int
    public let nextAvailableAt: Date?
}

public struct BipeHomeInvite: Decodable, Sendable, CustomStringConvertible {
    public let id: String
    public let code: String
    public let url: URL
    public let expiresAt: Date
    public var description: String { "BipeHomeInvite" }
}
