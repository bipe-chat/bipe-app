// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Foundation
import MatrixRustSDK
import SwiftUI

/// Type ChatRequestSummary of spec chat-requests. The trust line comes from the verified seal store when it shows.
struct MacChatRequest: Identifiable, Equatable {
    let roomID: String
    let inviterID: String
    let name: String
    let avatarURL: String?
    let isExternal: Bool
    let invitedAt: Date?
    var id: String { roomID }
}

enum MacRequestTrust: Equatable {
    case verified(name: String)
    case unverified
    case external

    /// Only a signed badge verified against the inviter's master key supplies a verified name.
    @MainActor static func of(_ request: MacChatRequest, seals: BipeCompanySeals?) -> Self {
        if request.isExternal { return .external }
        guard let badge = seals?.verifiedBadge(for: request.inviterID),
              !badge.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .unverified }
        return .verified(name: badge.name)
    }
}

/// Specs chat-requests and chat-request. Group invites stay out of this box: official ones join by themselves, and
/// the group invite rows of chat-list-filters belong to bipe-chat/bipe#1163.
@MainActor
final class MacChatRequests: ObservableObject {
    @Published private(set) var requests: [MacChatRequest] = []
    /// Rows that left the box at once while their action runs (offline-first).
    @Published private(set) var pending: Set<String> = []
    @Published private(set) var failed = false
    var visible: [MacChatRequest] { requests.filter { !pending.contains($0.roomID) } }
    var onRequests: (([MacChatRequest]) -> Void)?

    private let api: BipeAPIClient
    private var client: Client?
    private var userID = ""
    private var accessToken: () -> String? = { nil }
    private var subscriptions: BipeRoomSubscriptions?
    private var entries: RoomListEntriesWithDynamicAdaptersResult?
    private var streamTask: Task<Void, Never>?
    private var refreshTask: Task<Void, Never>?
    private var pairsTask: Task<Void, Never>?
    private var rooms: [Room] = []
    private var pairs: Set<String> = []
    private var lastPairAttempt = Date.distantPast
    private var generation = UUID()

    init(api: BipeAPIClient) { self.api = api }

    func attach(client: Client, service: RoomListService, userID: String, subscriptions: BipeRoomSubscriptions?,
                accessToken: @escaping () -> String?) async throws {
        reset()
        self.client = client
        self.userID = userID
        self.subscriptions = subscriptions
        self.accessToken = accessToken
        let generation = generation
        pairs = await Task.detached { BipeInviteStore(userID: userID).invitePairs() }.value
        let list = try await service.allRooms()
        guard self.generation == generation else { throw CancellationError() }
        let stream = RoomListEntriesStream()
        let entry = list.entriesWithDynamicAdapters(pageSize: 100, listener: stream)
        // Every invite. refresh keeps the requests: the Category filter reads m.direct, which the invitee writes only on join.
        _ = entry.controller().setFilter(kind: .all(filters: [.invite, .nonSpace, .deduplicateVersions]))
        entries = entry
        streamTask = Task { [weak self] in
            for await updates in stream.updates {
                guard let self, !Task.isCancelled, self.generation == generation else { return }
                for update in updates { self.rooms.apply(update.vectorDiff) }
                self.refresh()
                await self.subscriptions?.update(owner: "requests", roomIDs: self.rooms.map { $0.id() })
            }
        }
        loadPairs(minimumInterval: 60)
    }

    func reset() {
        generation = UUID()
        entries?.entriesStream().cancel(); entries = nil
        streamTask?.cancel(); streamTask = nil
        refreshTask?.cancel(); refreshTask = nil
        pairsTask?.cancel(); pairsTask = nil
        client = nil; subscriptions = nil; accessToken = { nil }
        rooms = []; requests = []; pending = []; pairs = []
        onRequests?([])
        lastPairAttempt = .distantPast; failed = false
    }

    /// ADR 0034: the per-account cache answers first. An unavailable API keeps the last confirmed pairs.
    private func loadPairs(minimumInterval: TimeInterval) {
        guard Date().timeIntervalSince(lastPairAttempt) >= minimumInterval, pairsTask == nil else { return }
        lastPairAttempt = Date()
        let generation = generation
        let userID = userID
        pairsTask = Task { [weak self] in
            guard let self else { return }
            defer { if self.generation == generation { self.pairsTask = nil } }
            let api = self.api
            guard let answer = try? await BipeAPIClient.withTokenRetry(accessToken: { self.accessToken() }, {
                (token: String) async throws(BipeAPIError) -> [String] in
                try await api.inviteConnections(accessToken: token)
            }), !Task.isCancelled, self.generation == generation else { return }
            let confirmed = Set(answer)
            self.pairs = confirmed
            await Task.detached { BipeInviteStore(userID: userID).setInvitePairs(confirmed) }.value
            self.refresh()
        }
    }

    private func refresh() {
        refreshTask?.cancel()
        let generation = generation
        let rooms = rooms
        refreshTask = Task { [weak self] in
            guard let self else { return }
            var summaries: [MacChatRequest] = []
            var unknownInviter = false
            for room in rooms {
                guard !Task.isCancelled, self.generation == generation else { return }
                guard let info = try? await room.roomInfo() else { continue }
                let inviter = info.inviter
                // A request from a blocked person never shows (Matrix ignore list).
                if inviter?.isIgnored == true { continue }
                let inviterID = inviter?.userId ?? ""
                let isDm = BipeInviteTrust.isDirectInvite(isDirect: info.isDirect, activeMembers: info.activeMembersCount,
                                                          serviceMembers: info.activeServiceMembersCount)
                // Group invites have no invite pairs: only an official inviter joins by itself there.
                if BipeInviteTrust.acceptsAutomatically(isDm: isDm, inviterID: inviterID, isIgnored: false,
                                                        pairs: isDm ? self.pairs : []) {
                    if self.pending.contains(info.id) { continue }
                    if await self.run(info.id, { try await $0.join() }) { continue }
                }
                guard isDm else { continue }
                if !inviterID.isEmpty { unknownInviter = true }
                var invitedAt: Date?
                if case .remoteInvite(let timestamp, _, _) = await room.latestEvent() {
                    invitedAt = Date(timeIntervalSince1970: Double(timestamp) / 1000)
                }
                let name = inviterID.isEmpty ? BipeStrings.dmNoName
                    : BipeChatRowName.person(userID: inviterID, displayName: inviter?.displayName, handle: nil)
                summaries.append(MacChatRequest(roomID: info.id, inviterID: inviterID, name: name, avatarURL: inviter?.avatarUrl,
                                                isExternal: !inviterID.isEmpty && !BipeServers.isBipeUserID(inviterID),
                                                invitedAt: invitedAt))
            }
            guard !Task.isCancelled, self.generation == generation else { return }
            summaries.sort { ($0.invitedAt ?? .distantPast) > ($1.invitedAt ?? .distantPast) }
            self.requests = summaries
            self.onRequests?(summaries)
            // A room that left the invite list takes its finished action with it, so a later invite can show.
            self.pending.formIntersection(rooms.map { $0.id() })
            // A person who just joined with this account's invite: the API answer runs refresh again and joins.
            if unknownInviter { self.loadPairs(minimumInterval: 5) }
        }
    }

    /// Action accept: joins the room. Returns the room ID to open, or nil after a failure.
    func accept(_ roomID: String, media: MacMedia) async -> String? {
        guard await run(roomID, { try await $0.join() }) else { return nil }
        // Spec timeline-media fromStranger: media in a DM accepted from the box waits for a tap on every device.
        await media.rememberRequestRoom(roomID)
        return roomID
    }

    /// Action decline: a plain leave, so the inviter can ask again later.
    func decline(_ roomID: String) async -> Bool { await run(roomID) { try await $0.leave() } }

    /// Action declineAndBlock: leave, then add the inviter to the ignore list.
    func declineAndBlock(_ request: MacChatRequest) async -> Bool {
        let client = client
        return await run(request.roomID) { room in
            try await room.leave()
            if !request.inviterID.isEmpty { try await client?.ignoreUser(userId: request.inviterID) }
        }
    }

    /// Action report: Room.reportRoom with no reason text, then leave.
    func report(_ roomID: String) async -> Bool {
        await run(roomID) { room in
            try await room.reportRoom(reason: "")
            try await room.leave()
        }
    }

    /// Offline-first: the row leaves at once; a failure brings it back with bipe_error_generic.
    private func run(_ roomID: String, _ action: (Room) async throws -> Void) async -> Bool {
        pending.insert(roomID)
        failed = false
        do {
            guard let room = try client?.getRoom(roomId: roomID) else { throw CancellationError() }
            try await action(room)
            return true
        } catch {
            pending.remove(roomID)
            failed = true
            return false
        }
    }
}

/// The requests row at the top of Conversas (spec chat-requests, rowCount). Hidden at 0.
struct MacRequestsEntry: View {
    let count: Int
    var body: some View {
        HStack(spacing: 12) {
            Image(decorative: "user-circle").resizable().frame(width: 24, height: 24)
                .foregroundStyle(BipeColor.accentInk)
                .frame(width: 48, height: 48).background(BipeColor.accentWash, in: Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(BipeStrings.requestsTitle).macFont(.headline)
                Text(BipeStrings.requestsRowBody).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
            }
            Spacer(minLength: 0)
            Text(count.formatted()).macFont(.caption).fontWeight(.semibold)
                .foregroundStyle(BipeColor.inkOnAccent).padding(.horizontal, 7).padding(.vertical, 2)
                .background(BipeColor.accentFill, in: Capsule())
        }.contentShape(Rectangle()).accessibilityElement(children: .combine)
    }
}

/// Spec chat-requests: one row of the box. hidePhotos shows the initials avatar, never the photo, until accepted.
struct MacRequestRow: View {
    let request: MacChatRequest
    @ObservedObject var media: MacMedia
    let seals: BipeCompanySeals?

    var body: some View {
        HStack(spacing: 12) {
            MacRequestAvatar(request: request, media: media, size: 48)
            VStack(alignment: .leading, spacing: 2) {
                Text(request.name).macFont(.headline).lineLimit(1)
                switch MacRequestTrust.of(request, seals: seals) {
                case .verified:
                    Text(BipeStrings.badgeVerified).macFont(.subheadline).foregroundStyle(BipeColor.trustVerifiedInk)
                case .unverified:
                    Text(BipeStrings.bannerUnverifiedTitle).macFont(.subheadline).foregroundStyle(BipeColor.trustCautionInk)
                case .external:
                    Text(BipeStrings.bannerExternal).macFont(.subheadline).foregroundStyle(BipeColor.trustNeutralInk)
                }
            }
        }.frame(minHeight: 56).contentShape(Rectangle())
    }
}

private struct MacRequestAvatar: View {
    let request: MacChatRequest
    @ObservedObject var media: MacMedia
    let size: CGFloat
    var body: some View {
        if media.showsInvitePhotos {
            MacAvatar(name: request.name, media: media, url: request.avatarURL, userID: request.inviterID, size: size)
        } else {
            MacAvatar(name: request.name, size: size)
        }
    }
}

/// Spec chat-request: who wants to talk, with their trust facts, before the person answers.
struct MacChatRequestView: View {
    let request: MacChatRequest
    @ObservedObject var requests: MacChatRequests
    @ObservedObject var account: MacAccount
    let opened: (String) -> Void
    let closed: () -> Void
    @State private var working = false
    @State private var confirmBlock = false
    @State private var reported = false
    @State private var facts: [String] = []
    @State private var photoMatch: String?

    private var trust: MacRequestTrust { .of(request, seals: account.seals) }
    /// Security rule 8: the contact name that the inviter's name copies. Contacts on the Mac are the joined DMs.
    private var contactCollision: String? {
        guard trust == .unverified else { return nil }
        return BipeNameLookalikes.contactCollision(name: request.name, contactNames: contacts.map(\.name))
    }
    private var contacts: [MacRoomSummary] {
        account.roomRows.filter { $0.isDm && $0.avatar.userID != request.inviterID && $0.avatar.userID != nil }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                MacRequestAvatar(request: request, media: account.media, size: 72)
                Text(request.name).macFont(.title2).fontWeight(.bold).multilineTextAlignment(.center)
                if let handle = account.handles?.handle(for: request.inviterID) {
                    Text("@\(handle)").macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
                // Spec chat-request profileFacts (ADR 0049), under the header. Never blocks accept or decline.
                ForEach(Array(facts.enumerated()), id: \.offset) { _, line in
                    Text(line).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary).multilineTextAlignment(.center)
                }
                trustBlock
                if let contactCollision { MacNotice(text: BipeStrings.warningNotYourContact(contactCollision)) }
                if let photoMatch { MacNotice(text: BipeStrings.warningPhotoMatch(photoMatch)) }
                if requests.failed { MacNotice(text: BipeStrings.errorGeneric, danger: true) }
                if reported { MacNotice(text: BipeStrings.dmReportedAndLeft) }
                actions.disabled(working || reported)
            }.padding(24).frame(maxWidth: 480).frame(maxWidth: .infinity)
        }
        .background(BipeColor.surfaceBase)
        .task(id: request.inviterID) {
            facts = []; photoMatch = nil
            if let lines = await account.profileFactLines(request.inviterID), !Task.isCancelled { facts = lines }
            let match = await findPhotoMatch()
            if !Task.isCancelled { photoMatch = match }
        }
        .alert(BipeStrings.appRequestDeclineBlock, isPresented: $confirmBlock) {
            Button(BipeStrings.appRequestDeclineBlock, role: .destructive) {
                act { await requests.declineAndBlock(request) }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.dmDeclineBlockAlert(request.name))
        }
    }

    @ViewBuilder private var trustBlock: some View {
        let verified: Bool = if case .verified = trust { true } else { false }
        VStack(spacing: 8) {
            switch trust {
            case .verified(let name):
                Text(BipeStrings.badgeVerifiedName(name)).macFont(.body).foregroundStyle(BipeColor.trustVerifiedInk)
            case .unverified:
                Text(BipeStrings.bannerUnverifiedTitle).macFont(.headline).foregroundStyle(BipeColor.trustCautionInk)
                Text(BipeStrings.requestUnverifiedBody).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
            case .external:
                Text(BipeStrings.bannerExternal).macFont(.body).foregroundStyle(BipeColor.trustNeutralInk)
            }
        }
        .multilineTextAlignment(.center).padding(16).frame(maxWidth: .infinity)
        .background(verified ? BipeColor.trustVerifiedWash : BipeColor.trustCautionWash, in: RoundedRectangle(cornerRadius: 12))
    }

    private var actions: some View {
        VStack(spacing: 10) {
            Button {
                Task {
                    working = true
                    let room = await requests.accept(request.roomID, media: account.media)
                    working = false
                    if let room { opened(room) }
                }
            } label: { Text(BipeStrings.appRequestAccept).frame(maxWidth: .infinity) }
                .buttonStyle(.bipeProminent)
            Button { act { await requests.decline(request.roomID) } } label: {
                Text(BipeStrings.appRequestDecline).frame(maxWidth: .infinity)
            }.buttonStyle(.bipeBordered)
            Button(role: .destructive) { confirmBlock = true } label: {
                Text(BipeStrings.appRequestDeclineBlock).frame(maxWidth: .infinity)
            }.buttonStyle(.bipeBordered)
            Text(BipeStrings.dmDeclineBlockDescription).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            Button(role: .destructive) {
                Task {
                    working = true
                    reported = await requests.report(request.roomID)
                    working = false
                }
            } label: { Text(BipeStrings.dmReport).frame(maxWidth: .infinity) }
                .buttonStyle(.bipeBordered)
            Text(BipeStrings.dmDeclineReportDescription).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
        }
    }

    private func act(_ action: @escaping () async -> Bool) {
        Task {
            working = true
            let done = await action()
            working = false
            if done { closed() }
        }
    }

    /// Spec chat-request photoMatch (ADR 0049): an unverified inviter's avatar against the avatars of joined DMs.
    /// Hashes stay in this function: no image or hash leaves the Mac. Hidden photos are not fetched for this check.
    private func findPhotoMatch() async -> String? {
        guard trust == .unverified, account.media.showsInvitePhotos, let url = request.avatarURL,
              let own = await hash(url) else { return nil }
        for row in contacts.prefix(200) {
            guard !Task.isCancelled else { return nil }
            guard let other = row.avatar.url, other != url, let theirs = await hash(other) else { continue }
            if BipePhotoMatch.matches(own, theirs) { return row.name }
        }
        return nil
    }

    private func hash(_ url: String) async -> String? {
        guard let image = await account.media.avatar(url: url, userID: nil, size: 64)?
            .cgImage(forProposedRect: nil, context: nil, hints: nil),
              let grid = BipePhotoMatch.grid(of: image) else { return nil }
        return BipePhotoMatch.hash(gray: grid)
    }
}

/// Start a DM by @ (spec chat-search notes: on Bipe a person is found by their @). The DM is created when missing.
struct MacStartChatSheet: View {
    @ObservedObject var account: MacAccount
    let opened: (String) -> Void
    let close: () -> Void
    @State private var input = ""
    @State private var working = false
    @State private var notice: String?

    private var handle: String { BipeHandle.sanitize(input).lowercased() }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.actionStartChat).macFont(.headline)
            TextField(BipeStrings.actionStartChat, text: $input).textFieldStyle(.roundedBorder)
                .onSubmit(start).disabled(working)
            Text(BipeStrings.startChatSearchByHandle).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if let notice { MacNotice(text: notice, danger: true) }
            HStack {
                Spacer()
                if working { ProgressView() }
                Button(BipeStrings.appCancel, action: close).keyboardShortcut(.cancelAction)
                Button(BipeStrings.invitesChat, action: start).keyboardShortcut(.defaultAction)
                    .disabled(working || handle.isEmpty)
            }
        }.padding(MacDesign.gap * 2).frame(width: 420)
    }

    private func start() {
        guard !working, !handle.isEmpty else { return }
        let handle = handle
        working = true; notice = nil
        Task {
            defer { working = false }
            do {
                opened(try await account.openChat(handle: handle))
            } catch MacAccount.StartChatError.notFound {
                notice = BipeStrings.startChatHandleNotFound(handle)
            } catch NativeDm.CreateError.inviteRefused {
                notice = BipeStrings.appGroupInviteRefused("@" + handle)
            } catch {
                notice = BipeStrings.errorGeneric
            }
        }
    }
}
