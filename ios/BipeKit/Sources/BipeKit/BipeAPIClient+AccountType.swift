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

}

private struct HomeAccountType: Decodable {
    let accountType: String
}
