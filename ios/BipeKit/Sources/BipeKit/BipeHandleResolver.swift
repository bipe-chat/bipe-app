// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The @ of Matrix IDs, from `POST /v1/handles/resolve`, cached per user ID for the life of the process.
/// People never see the Matrix ID (ADR 0013): a screen shows the @ when there is one.
@MainActor
public final class BipeHandleResolver {
    private let api: BipeAPIClient
    private let accessToken: @MainActor () -> String?
    /// User ID to handle; a cached `nil` means the user has no handle.
    private var cache: [String: String?] = [:]

    public init(api: BipeAPIClient, accessToken: @escaping @MainActor () -> String?) {
        self.api = api
        self.accessToken = accessToken
    }

    /// The cached handle, without a request.
    public func cachedHandle(for userID: String) -> String?? {
        cache[userID]
    }

    /// The handles of `userIDs`. IDs without a handle are absent. A failed request leaves them out and uncached.
    public func handles(for userIDs: [String]) async -> [String: String] {
        let missing = Array(Set(userIDs.filter { cache[$0] == nil }))
        if !missing.isEmpty {
            let api = api
            let resolved = try? await BipeAPIClient.withTokenRetry(accessToken: accessToken) { (token: String) async throws(BipeAPIError) -> [String: String] in
                try await api.resolveHandles(userIDs: missing, accessToken: token)
            }
            if let resolved {
                for userID in missing {
                    cache[userID] = .some(resolved[userID])
                }
            }
        }
        var result: [String: String] = [:]
        for userID in userIDs {
            if case .some(.some(let handle)) = cache[userID] {
                result[userID] = handle
            }
        }
        return result
    }

    public func handle(for userID: String) async -> String? {
        await handles(for: [userID])[userID]
    }

    /// Forgets a user's handle, for example after the user changed their own.
    public func forget(_ userID: String) {
        cache[userID] = nil
    }
}
