// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type PersonalInvite of spec invites.
struct PersonalInvite: Identifiable, Equatable {
    var id: String
    var status: BipeInviteStatus
    var name: String?
    var expiresAt: Date
    /// The link kept on this phone. nil when the invite was made on another device.
    var link: URL?
    /// used only: who joined.
    var joined: Person?
    /// pending only: a note waits on this phone (ADR 0136).
    var hasNote: Bool
}

/// Spec invites (bipe-shared product/screens/invites.json).
@MainActor
final class InvitesModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed
    }

    @Published private(set) var available: Int?
    @Published private(set) var nextAvailableAt: Date?
    @Published private(set) var pending: [PersonalInvite] = []
    @Published private(set) var joined: [PersonalInvite] = []
    @Published private(set) var phase = Phase.loading
    @Published var error: String?

    let session: AccountSession
    let api: BipeAPIClient
    private let store: BipeInviteStore

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        store = BipeInviteStore(userID: session.userId)
    }

    /// The text under the create button (BipeInviteQuota).
    var footer: String {
        BipeInviteQuota.footer(available: available, nextAvailableAt: nextAvailableAt)
    }

    /// Action appear: GET /v1/me/invites. Drops the kept links and notes of invites that are no longer pending.
    func appear() async {
        if available == nil { phase = .loading }
        do {
            let mine = try await authorized { (token: String) async throws(BipeAPIError) -> BipeMyInvites in
                try await api.myInvites(accessToken: token)
            }
            available = mine.available
            nextAvailableAt = mine.nextAvailableAt
            let pendingIDs = Set(mine.invites.filter { $0.status == .pending }.map(\.id))
            store.keepLinks(forPendingInviteIDs: pendingIDs)
            for id in store.notes().keys where !pendingIDs.contains(id) {
                store.removeNote(forInviteID: id)
            }
            let links = store.links()
            let notes = store.notes()
            pending = mine.invites.filter { $0.status == .pending }.map { invite in
                PersonalInvite(id: invite.id, status: invite.status, name: invite.name, expiresAt: invite.expiresAt,
                               link: links[invite.id], joined: nil, hasNote: notes[invite.id] != nil)
            }
            var joinedInvites: [PersonalInvite] = []
            for invite in mine.invites where invite.status == .used {
                joinedInvites.append(PersonalInvite(id: invite.id, status: invite.status, name: invite.name, expiresAt: invite.expiresAt,
                                                    link: nil, joined: await person(invite.joinedUserId), hasNote: false))
            }
            joined = joinedInvites
            phase = .loaded
        } catch {
            if available == nil { phase = .failed }
            self.error = (error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork
        }
    }

    /// Action setName: PUT /v1/me/invites/{inviteId}. Empty removes it.
    func setName(_ name: String, for id: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try await authorized { (token: String) async throws(BipeAPIError) in
                try await api.nameInvite(id: id, name: trimmed, accessToken: token)
            }
            await appear()
        } catch {
            self.error = (error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork
        }
    }

    /// Action revoke: DELETE /v1/me/invites/{inviteId}, then the link and the note go from this phone.
    func revoke(_ id: String) async {
        do {
            try await authorized { (token: String) async throws(BipeAPIError) in
                try await api.revokeInvite(id: id, accessToken: token)
            }
            store.removeNote(forInviteID: id)
            await appear()
        } catch {
            self.error = (error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork
        }
    }

    /// Action openChat: the DM with the person who joined (Client.getDmRoom).
    func dmRoomId(with userId: String) -> String? {
        (try? session.client.getDmRoom(userId: userId))?.id()
    }

    private func person(_ userId: String?) async -> Person? {
        guard let userId else { return nil }
        let profile = try? await session.client.getProfile(userId: userId)
        return Person(userId: userId, displayName: profile?.displayName, avatarUrl: profile?.avatarUrl)
    }

    /// A bipe-api request with the account's access token, retried once after a token refresh.
    private func authorized<T>(_ request: (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, request)
    }
}
