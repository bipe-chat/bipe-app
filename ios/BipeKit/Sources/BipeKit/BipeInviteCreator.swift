// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Creates personal invites and keeps each link on this device. The invites, contacts and start chat screens share it.
@MainActor
public struct BipeInviteCreator {
    public enum Result {
        case created(BipeCreatedInvite)
        case noneLeft(nextAvailableAt: Date?)
    }
    
    private let apiClient: BipeAPIClient
    private let accessToken: @MainActor () -> String?
    private let store: BipeInviteStore
    
    public init(apiClient: BipeAPIClient, userID: String, accessToken: @escaping @MainActor () -> String?) {
        self.apiClient = apiClient
        self.accessToken = accessToken
        store = BipeInviteStore(userID: userID)
    }
    
    /// Creates an invite and stores its link, and its note when there is one (ADR 0136). The caller checks the quota first.
    public func create(note: String? = nil) async throws(BipeAPIError) -> BipeCreatedInvite {
        let created = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { [apiClient] (token: String) async throws(BipeAPIError) -> BipeCreatedInvite in
            try await apiClient.createInvite(accessToken: token)
        }
        store.saveLink(created.url, forInviteID: created.id)
        if let note {
            store.saveNote(note, forInviteID: created.id)
        }
        return created
    }
    
    /// Reads the quota, then creates an invite only when one is left. For screens that do not show the quota.
    public func createIfAvailable(note: String? = nil) async throws(BipeAPIError) -> Result {
        let myInvites = try await quota()
        guard myInvites.available > 0 else { return .noneLeft(nextAvailableAt: myInvites.nextAvailableAt) }
        return try await .created(create(note: note))
    }
    
    /// The account's invites and how many are left. Creates nothing.
    public func quota() async throws(BipeAPIError) -> BipeMyInvites {
        try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { [apiClient] (token: String) async throws(BipeAPIError) -> BipeMyInvites in
            try await apiClient.myInvites(accessToken: token)
        }
    }
}

public enum BipeInviteQuota {
    /// The text under the invite buttons. `available` is `nil` while the count loads.
    public static func footer(available: Int?, nextAvailableAt: Date?) -> String {
        guard let available else { return BipeStrings.invitesExplainer }
        guard available > 0 else { return noneLeft(nextAvailableAt: nextAvailableAt) }
        // A large count means nothing to the person, so it shows only when few invites are left.
        guard available <= lowInviteCount else { return BipeStrings.invitesExplainer }
        return "\(BipeStrings.invitesExplainer) \(BipeStrings.invitesAvailable(String(available)))"
    }
    
    public static func noneLeft(nextAvailableAt: Date?) -> String {
        guard let nextAvailableAt else { return BipeStrings.invitesNoneLeft }
        return "\(BipeStrings.invitesNoneLeft) \(BipeStrings.invitesNext(nextAvailableAt.formatted(date: .abbreviated, time: .shortened)))"
    }
    
    private static let lowInviteCount = 5
}
