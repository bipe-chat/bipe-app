// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

public extension BipeAPIClient {
    /// `GET /v1/banner` (spec remote-banner, ADR 0117). No auth; sends nothing about the person. `nil` when the server
    /// has no banner or it is past `expires_at`.
    func remoteBanner() async throws(BipeAPIError) -> BipeRemoteBanner? {
        let response: BipeRemoteBannerResponse = try await send(method: "GET", path: ["v1", "banner"], body: Empty?.none)
        return response.banner
    }
}
