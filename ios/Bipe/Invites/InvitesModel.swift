// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Specs invites and invite-note. One model per account, alive even when the invite screen is closed.
@MainActor
final class InvitesModel: ObservableObject {
    enum Phase { case loading, loaded, failed }
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var quota: BipeMyInvites?
    @Published private(set) var links: [String: URL] = [:]
    @Published private(set) var notes: Set<String> = []
    @Published private(set) var created: BipeCreatedInvite?
    @Published var error: String?
    @Published var notice: String?
    let session: AccountSession
    private let api: BipeAPIClient
    private let store: BipeInviteStore
    private var busy = false
    private var delivering = false
    private var refreshTask: Task<Void, Never>?

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        store = BipeInviteStore(userID: session.userId)
    }

    deinit { refreshTask?.cancel() }
    func close() { refreshTask?.cancel(); refreshTask = nil }

    func start() {
        guard refreshTask == nil else { return }
        refreshTask = Task { [weak self] in
            await self?.refresh()
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
                guard let self else { return }
                if !self.store.notes().isEmpty { await self.refresh() }
            }
        }
    }

    var pending: [BipeInvite] { quota?.invites.filter { $0.status == .pending }.sorted { $0.createdAt > $1.createdAt } ?? [] }
    var joined: [BipeInvite] { quota?.invites.filter { $0.status == .used }.sorted { $0.createdAt > $1.createdAt } ?? [] }

    func refresh() async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            let response = try await withToken { (token: String) async throws(BipeAPIError) -> BipeMyInvites in
                try await self.api.myInvites(accessToken: token)
            }
            quota = response
            _ = store.keepLinks(forPendingInviteIDs: Set(response.invites.filter { $0.status == .pending }.map(\.id)))
            for invite in response.invites where invite.status == .revoked || invite.status == .expired {
                _ = store.removeNote(forInviteID: invite.id)
            }
            links = store.links()
            notes = Set(store.notes().keys)
            phase = .loaded
            let joined = Set(response.invites.filter { $0.status == .used && $0.joinedUserId != nil }.map(\.id))
            let seen = store.seenJoinedInviteIDs()
            store.setSeenJoinedInviteIDs((seen ?? []).union(joined))
            if let seen {
                for invite in response.invites where joined.contains(invite.id) && !seen.contains(invite.id) {
                    if let userId = invite.joinedUserId {
                        let name = (try? await session.client.getProfile(userId: userId))?.displayName ?? Person.localpart(of: userId)
                        notice = BipeStrings.invitesJoined(name)
                    }
                }
            }
            if let pairs = try? await withToken({ (token: String) async throws(BipeAPIError) -> [String] in
                try await self.api.inviteConnections(accessToken: token)
            }) { store.setInvitePairs(Set(pairs)) }
            await retryNotes()
        } catch {
            if !Task.isCancelled { phase = .failed }
        }
    }

    func beginCreate() { created = nil; error = nil }

    /// Once POST succeeds, retries persist/name that same invite. They never consume another invite slot.
    func create(name: String, note: String) async {
        error = nil
        do {
            if created == nil {
                created = try await withToken { (token: String) async throws(BipeAPIError) -> BipeCreatedInvite in
                    try await self.api.createInvite(accessToken: token)
                }
            }
            guard let created else { return }
            guard store.saveLink(created.url, forInviteID: created.id), store.saveNote(note, forInviteID: created.id) else {
                error = BipeStrings.errorGeneric
                return
            }
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { try await setName(id: created.id, name: trimmed) }
            await refresh()
        } catch {
            self.error = (error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork
        }
    }

    func setName(id: String, name: String) async throws {
        try await withToken { (token: String) async throws(BipeAPIError) -> Void in
            try await self.api.nameInvite(id: id, name: name.trimmingCharacters(in: .whitespacesAndNewlines), accessToken: token)
        }
        await refresh()
    }

    func revoke(_ invite: BipeInvite) async {
        do {
            try await withToken { (token: String) async throws(BipeAPIError) -> Void in
                try await self.api.revokeInvite(id: invite.id, accessToken: token)
            }
            _ = store.removeNote(forInviteID: invite.id)
            await refresh()
        } catch { self.error = (error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork }
    }

    /// Wait for the invitee's existing, joined and encrypted DM. Never create a second chat to send the note.
    func retryNotes() async {
        guard !delivering, let invites = quota?.invites else { return }
        delivering = true
        defer { delivering = false; notes = Set(store.notes().keys) }
        for invite in invites where invite.status == .used {
            guard !Task.isCancelled, let userId = invite.joinedUserId, userId != session.userId,
                  store.notes()[invite.id] != nil,
                  let room = try? session.client.getDmRoom(userId: userId),
                  let info = try? await room.roomInfo(), info.membership == .joined, info.isDm,
                  await room.isEncrypted(),
                  let timeline = try? await room.timeline(),
                  let note = store.takeNote(forInviteID: invite.id) else { continue }
            do {
                let content = try messageEventContentNew(msgtype: .text(content: TextMessageContent(body: note, formatted: nil)))
                _ = try await timeline.send(msg: content)
            } catch { _ = store.saveNote(note, forInviteID: invite.id) }
        }
    }

    private func withToken<T>(_ operation: @MainActor (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, operation)
    }
}
