// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Type ChatRequestSummary of spec chat-requests, also used for the group invites of chat-list-filters.
struct ChatRequestSummary: Identifiable, Equatable {
    enum Trust: Equatable {
        case verified(name: String)
        case unverified
        case external
    }

    var roomId: String
    var inviterId: String
    /// The inviter's name for a request, the group name for a group invite.
    var name: String
    var inviterName: String?
    var avatarUrl: String?
    var trust: Trust
    var invitedAt: Date?
    var isDm = false

    var id: String { roomId }
}

/// Specs chat-requests and chat-request, and the group invites of chat-list-filters.
@MainActor
final class ChatRequestsModel: ObservableObject {
    /// Requests: DM invites, newest first. They never show in chat-list.
    @Published private(set) var requests: [ChatRequestSummary] = []
    /// Group invites, newest first, for the top of the groups list.
    @Published private(set) var groupInvites: [ChatRequestSummary] = []
    /// Rows that left the box at once while their action runs (offline-first).
    @Published private(set) var pending: Set<String> = []
    @Published var error = false

    let session: AccountSession
    let people: PeopleService
    private let api: BipeAPIClient
    private var invitePairs: Set<String> = []
    private var dmRooms: [Room] = []
    private var lastPairAttempt = Date.distantPast
    private var checkingPairs = false
    private var trustTask: Task<Void, Never>?
    private var pairTask: Task<Void, Never>?
    private var dmInvites: FilteredRoomList?
    private var groupInviteList: FilteredRoomList?
    private var started = false

    init(session: AccountSession, api: BipeAPIClient, people: PeopleService) {
        self.session = session
        self.api = api
        self.people = people
    }

    /// Only a signed company payload verified against the inviter's master key supplies a verified name.
    /// The observable seal store updates readers and suppresses expired/revoked/invalid badges.
    func trust(for request: ChatRequestSummary) -> ChatRequestSummary.Trust {
        if request.trust == .external { return .external }
        guard let badge = people.seals.badge(for: request.inviterId),
              !badge.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .unverified }
        return .verified(name: badge.name)
    }

    deinit { trustTask?.cancel(); pairTask?.cancel() }

    /// The privacy switch bipe_privacy_requests_hide_photos (settings-group, kept on the phone).
    var hidePhotos: Bool {
        UserDefaults.standard.object(forKey: SettingsKeys.hideRequestPhotos) as? Bool ?? true
    }

    /// rowCount of chat-requests: 0 hides the row.
    var rowCount: Int { visibleRequests.count }

    var visibleRequests: [ChatRequestSummary] { requests.filter { !pending.contains($0.roomId) } }

    var visibleGroupInvites: [ChatRequestSummary] { groupInvites.filter { !pending.contains($0.roomId) } }

    func start() async {
        guard !started else { return }
        started = true
        await loadInvitePairs()
        let dms = FilteredRoomList { [weak self] rooms in self?.refresh(rooms, groups: false) }
        let groups = FilteredRoomList { [weak self] rooms in self?.refresh(rooms, groups: true) }
        dmInvites = dms
        groupInviteList = groups
        // A request is an invite with Category People; a group invite is an invite with Category Group.
        await dms.start(session: session, filter: .all(filters: [.invite, .nonSpace, .deduplicateVersions, .category(expect: .people)]))
        await groups.start(session: session, filter: .all(filters: [.invite, .nonSpace, .deduplicateVersions, .category(expect: .group)]))
        trustTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(30)) } catch { return }
                guard let self else { return }
                if !self.requests.isEmpty { await self.refreshTrust() }
            }
        }
    }

    /// ADR 0034: read the per-account cache first; an unavailable API keeps the last confirmed pairs.
    private func loadInvitePairs() async {
        guard !checkingPairs else { return }
        checkingPairs = true
        defer { checkingPairs = false }
        let userId = session.userId
        invitePairs = await Task.detached { BipeInviteStore(userID: userId).invitePairs() }.value
        guard Date().timeIntervalSince(lastPairAttempt) >= 60, !Task.isCancelled else { return }
        lastPairAttempt = Date()
        pairTask = Task { [weak self] in
            guard let self else { return }
            if let pairs = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, {
                (token: String) async throws(BipeAPIError) -> [String] in
                try await self.api.inviteConnections(accessToken: token)
            }), !Task.isCancelled {
                let confirmed = Set(pairs)
                self.invitePairs = confirmed
                await Task.detached { BipeInviteStore(userID: userId).setInvitePairs(confirmed) }.value
                self.refresh(self.dmRooms, groups: false)
            }
        }
    }

    func refreshTrust() async {
        await loadInvitePairs()
        refresh(dmRooms, groups: false)
    }

    private func refresh(_ rooms: [Room], groups: Bool) {
        if !groups { dmRooms = rooms }
        Task {
            if !groups { await loadInvitePairs() }
            var summaries: [ChatRequestSummary] = []
            for room in rooms {
                guard !Task.isCancelled else { return }
                if let summary = await Self.summary(of: room, ownServer: Self.server(of: session.userId)) {
                    if !groups && BipeInviteTrust.acceptsAutomatically(isDm: summary.isDm, inviterID: summary.inviterId,
                                                                       isIgnored: false, pairs: invitePairs) {
                        if pending.contains(summary.roomId) { continue }
                        if await run(summary.roomId, { try await $0.join() }) { continue }
                    }
                    summaries.append(summary)
                }
            }
            guard !Task.isCancelled else { return }
            summaries.sort { ($0.invitedAt ?? .distantPast) > ($1.invitedAt ?? .distantPast) }
            if groups {
                groupInvites = summaries
            } else {
                requests = summaries
            }
        }
    }

    private static func summary(of room: Room, ownServer: String) async -> ChatRequestSummary? {
        guard let info = try? await room.roomInfo() else { return nil }
        let inviter = info.inviter
        // A request from a blocked person never shows (Matrix ignore list).
        if inviter?.isIgnored == true { return nil }
        var invitedAt: Date?
        if case .remoteInvite(let timestamp, _, _) = await room.latestEvent() {
            invitedAt = Date(milliseconds: timestamp)
        }
        let inviterId = inviter?.userId ?? ""
        let inviterName = inviter?.displayName.flatMap { $0.isEmpty ? nil : $0 } ?? (inviterId.isEmpty ? nil : Person.localpart(of: inviterId))
        return ChatRequestSummary(
            roomId: info.id,
            inviterId: inviterId,
            name: info.isDm ? (inviterName ?? BipeStrings.dmNoName) : (info.displayName ?? BipeStrings.tabGroups),
            inviterName: inviterName,
            avatarUrl: info.isDm ? inviter?.avatarUrl : info.avatarUrl,
            // Local requests start unverified; views observe the shared, verified company-seal store.
            trust: server(of: inviterId) == ownServer || inviterId.isEmpty ? .unverified : .external,
            invitedAt: invitedAt,
            isDm: info.isDm)
    }

    private static func server(of userId: String) -> String {
        userId.split(separator: ":", maxSplits: 1).dropFirst().first.map(String.init) ?? ""
    }

    // MARK: - Actions (chat-request, and the invite rows of chat-list-filters)

    /// Action accept: joins the room. Returns the room ID to open in chat-timeline, or nil after a failure.
    func accept(_ roomId: String) async -> String? {
        await run(roomId) { room in try await room.join() } ? roomId : nil
    }

    /// Action decline: a plain leave, so the inviter can ask again later.
    func decline(_ roomId: String) async {
        _ = await run(roomId) { room in try await room.leave() }
    }

    /// Action declineAndBlock: leave, then add the inviter to the ignore list.
    func declineAndBlock(_ request: ChatRequestSummary) async {
        let client = session.client
        _ = await run(request.roomId) { room in
            try await room.leave()
            if !request.inviterId.isEmpty {
                try await client.ignoreUser(userId: request.inviterId)
            }
        }
    }

    /// Action report: Room.reportRoom with no reason text, then leave. True when it went through.
    func report(_ roomId: String) async -> Bool {
        await run(roomId) { room in
            try await room.reportRoom(reason: "")
            try await room.leave()
        }
    }

    /// Offline-first: the row leaves at once; a failure brings it back with bipe_error_generic.
    private func run(_ roomId: String, _ action: (Room) async throws -> Void) async -> Bool {
        pending.insert(roomId)
        error = false
        do {
            guard let room = try session.client.getRoom(roomId: roomId) else { throw CancellationError() }
            try await action(room)
            return true
        } catch {
            pending.remove(roomId)
            self.error = true
            return false
        }
    }
}
