// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// A Matrix OpenID token (Matrix spec client-server, OpenID). The core bipe-api routes take it in place of the
/// Synapse access token (bipe-chat/bipe#646, contract schemes `matrixOpenId` and `matrixServerName`).
public struct BipeOpenIDToken: Sendable, Equatable {
    public let accessToken: String
    public let matrixServerName: String
    /// The app asks for a new token after this time.
    let renewAt: Date
}

/// Requests and caches the account's OpenID token, in memory only. One instance per account session.
/// A token is renewed ``renewMargin`` before `expires_in` ends.
@MainActor
public final class BipeOpenIDTokens {
    /// Time before the end of `expires_in` at which the app asks for a new token.
    static let renewMargin: TimeInterval = 60

    private let homeserver: BipeAPIClient?
    private let userID: String
    private let accessToken: @MainActor () -> String?
    private var cached: BipeOpenIDToken?
    private var pending: Task<Result<BipeOpenIDToken, BipeAPIError>, Never>?

    /// `accessToken` gives the Synapse access token. It is read before every request: the SDK may refresh it.
    public nonisolated init(homeserverURL: String, userID: String, accessToken: @escaping @MainActor () -> String?) {
        homeserver = URL(string: homeserverURL).map { BipeAPIClient(baseURL: $0) }
        self.userID = userID
        self.accessToken = accessToken
    }

    /// The cached token, or a new one when there is none or it is near its end.
    public func token() async throws(BipeAPIError) -> BipeOpenIDToken {
        if let cached, Date() < cached.renewAt {
            return cached
        }
        return try await request()
    }

    /// A new token after bipe-api refused `rejected`. A token that another call got after `rejected` is used as is.
    public func token(replacing rejected: BipeOpenIDToken) async throws(BipeAPIError) -> BipeOpenIDToken {
        if let cached, cached != rejected, Date() < cached.renewAt {
            return cached
        }
        cached = nil
        return try await request()
    }

    /// `POST /_matrix/client/v3/user/{userId}/openid/request_token` with an empty body. Calls at the same time share one request.
    private func request() async throws(BipeAPIError) -> BipeOpenIDToken {
        let task = pending ?? Task { await self.fetch() }
        pending = task
        let result = await task.value
        if pending == task {
            pending = nil
        }
        let token = try result.get()
        cached = token
        return token
    }

    private func fetch() async -> Result<BipeOpenIDToken, BipeAPIError> {
        guard let homeserver else { return .failure(.unexpectedResponse(statusCode: nil)) }
        let userID = userID
        do {
            let response = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { (token: String) async throws(BipeAPIError) -> OpenIDResponse in
                try await homeserver.send(method: "POST", path: ["_matrix", "client", "v3", "user", userID, "openid", "request_token"],
                                          body: BipeAPIClient.Empty(), accessToken: token)
            }
            let lifetime = max(TimeInterval(response.expiresIn) - Self.renewMargin, 0)
            return .success(BipeOpenIDToken(accessToken: response.accessToken, matrixServerName: response.matrixServerName,
                                            renewAt: Date().addingTimeInterval(lifetime)))
        } catch {
            return .failure(error)
        }
    }

    private struct OpenIDResponse: Decodable {
        let accessToken: String
        let matrixServerName: String
        let expiresIn: Int
    }
}

public extension BipeAPIClient {
    /// Runs `request` with the account's OpenID token. On HTTP 401 it asks for a new token and retries once.
    @MainActor static func withOpenIDRetry<T>(_ tokens: BipeOpenIDTokens,
                                              _ request: (BipeOpenIDToken) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        let token = try await tokens.token()
        do {
            return try await request(token)
        } catch {
            guard error.isUnauthorized else { throw error }
            return try await request(try await tokens.token(replacing: token))
        }
    }
}
