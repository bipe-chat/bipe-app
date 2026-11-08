// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// `ReachStatus` of `GET /v1/me/reach` (security.md rule 11, bipe-chat/bipe#851): the first-contact tier and budget.
/// The budgets are server configuration; the app shows what the server sends and computes nothing.
public struct BipeReachStatus: Decodable, Sendable, Equatable {
    public enum Tier: String, Decodable, Sendable {
        case unattested, new, normal, verified, company, slowed
    }

    /// The step that raises the budget. Absent: no step the owner can take.
    public enum RaiseBy: String, Decodable, Sendable {
        case verify, wait
    }

    public let tier: Tier
    /// First contacts allowed per rolling window.
    public let budget: Int
    /// First contacts in the window.
    public let used: Int
    /// When one unit comes back. `nil` when `used` is 0.
    public let resetsAt: Date?
    public let raiseBy: RaiseBy?

    public init(tier: Tier, budget: Int, used: Int, resetsAt: Date?, raiseBy: RaiseBy?) {
        self.tier = tier
        self.budget = budget
        self.used = used
        self.resetsAt = resetsAt
        self.raiseBy = raiseBy
    }

    /// Spec person-profile reach notice: the budget is spent.
    public var isSpent: Bool { used >= budget }
}

public extension BipeAPIClient {
    /// `GET /v1/me/reach` (bipe-chat/bipe#846).
    func reach(accessToken: String) async throws(BipeAPIError) -> BipeReachStatus {
        try await send(method: "GET", path: ["v1", "me", "reach"], body: Empty?.none, accessToken: accessToken)
    }
}
