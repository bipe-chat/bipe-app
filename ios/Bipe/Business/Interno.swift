// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec interno (bipe ADR 0284 phase B): the member sessions of this phone. Each work account row can hold a second SDK
/// session, the person's member identity, for chats between the people of the account. A member session never shows
/// in the account switcher and never counts against maxPersonalAccounts: AccountStartModel never lists it.
///
/// The saved session sits in SessionStore under the member's Matrix ID, like any session, so the notification service
/// opens it for a push. The password from POST /v1/me/business/members/session lives only for the sign-in.
@MainActor
final class InternoSessions: ObservableObject {
    static let shared = InternoSessions()

    /// The open member sessions, by the work account's Matrix ID.
    @Published private(set) var sessions: [String: AccountSession] = [:]
    /// The room list of each member session, by the member's Matrix ID.
    private var chatLists: [String: ChatListModel] = [:]
    private var listTasks: [String: Task<Void, Never>] = [:]
    /// Work accounts whose first sign-in runs now.
    private var opening: Set<String> = []
    /// Work accounts whose launch restore runs or ran in this process.
    private var restoring: Set<String> = []

    enum Failure: Error {
        case notMember
        case busy
    }

    private init() {}

    func session(forWork workId: String) -> AccountSession? {
        sessions[workId]
    }

    /// The work account a member session belongs to.
    func workAccount(ofMember memberId: String) -> String? {
        sessions.first { $0.value.userId == memberId }?.key
    }

    func chatList(forWork workId: String) -> ChatListModel? {
        sessions[workId].flatMap { chatLists[$0.userId] }
    }

    func chatList(for member: AccountSession) -> ChatListModel {
        if let list = chatLists[member.userId] { return list }
        let list = ChatListModel(session: member)
        chatLists[member.userId] = list
        listTasks[member.userId] = Task { await list.start() }
        return list
    }

    // MARK: Which member identity belongs to which work account (phone side only)

    private static func key(_ workId: String) -> String { "bipe.interno.member.\(workId)" }

    private static func memberId(forWork workId: String) -> String? {
        UserDefaults.standard.string(forKey: key(workId))
    }

    private static func remember(_ memberId: String?, forWork workId: String) {
        if let memberId {
            UserDefaults.standard.set(memberId, forKey: key(workId))
        } else {
            UserDefaults.standard.removeObject(forKey: key(workId))
        }
    }

    // MARK: Open

    /// Later launches: restores the saved member session of the work account, when this phone has one. No call to
    /// bipe-api. A restore that fails for a local reason keeps everything for the next open.
    func restore(forWork work: AccountSession) async {
        guard sessions[work.userId] == nil, !restoring.contains(work.userId),
              let memberId = Self.memberId(forWork: work.userId), BipeMemberIdentity.isMember(memberId),
              let saved = SessionStore.load(userId: memberId) else { return }
        restoring.insert(work.userId)
        defer { restoring.remove(work.userId) }
        do {
            let member = try await AccountSession.restore(saved)
            guard sessions[work.userId] == nil else { return }
            add(member, work: work)
        } catch AccountStep.Failure.noStore {
            // A saved session from an earlier install: the next open signs in again.
            SessionStore.delete(userId: memberId)
        } catch {
            AccountStep.log.error("interno: member restore failed, kept for later")
        }
    }

    /// Action open of spec interno: the member session of the work account. On the first open on this phone, POST
    /// /v1/me/business/members/session and m.login.password with that user on the work account's server. Later opens
    /// reuse the session. One sign-in at a time per work account.
    func open(forWork work: AccountSession, api: BipeBusinessAPI) async throws -> AccountSession {
        if let member = sessions[work.userId] { return member }
        let workId = work.userId
        // The launch restore uses the same store: never sign in over it while it runs.
        for _ in 0..<600 where restoring.contains(workId) {
            try await Task.sleep(for: .milliseconds(100))
        }
        await restore(forWork: work)
        if let member = sessions[workId] { return member }
        guard !opening.contains(workId) else {
            // Another screen signs in now: wait for it.
            for _ in 0..<600 {
                try await Task.sleep(for: .milliseconds(100))
                if let member = sessions[workId] { return member }
                if !opening.contains(workId) { break }
            }
            throw Failure.busy
        }
        opening.insert(workId)
        defer { opening.remove(workId) }
        let grant = try await api.memberSession()
        guard BipeMemberIdentity.isMember(grant.userId) else { throw Failure.notMember }
        // The password goes no further than this sign-in (ADR 0284, ADR 0283): never saved, never logged.
        let member = try await AccountSession.signIn(userId: grant.userId, password: grant.password,
                                                     homeserverURL: work.client.homeserver())
        Self.remember(member.userId, forWork: workId)
        await Self.setUpCrossSigning(member)
        if let existing = sessions[workId] { return existing }
        add(member, work: work)
        return member
    }

    /// Spec interno open: the person's phone makes the member identity's cross-signing keys (ADR 0284 point 4), with no
    /// recovery key prompt. The client builder already does it at sign-in (autoEnableCrossSigning); this covers a
    /// sign-in where that step did not finish. Only a read that succeeds and finds no identity makes one: an identity
    /// made on another surface stays.
    private static func setUpCrossSigning(_ member: AccountSession) async {
        let encryption = member.client.encryption()
        await encryption.waitForE2eeInitializationTasks()
        let identity: UserIdentity?
        do {
            identity = try await encryption.userIdentity(userId: member.userId, fallbackToServer: true)
        } catch {
            return
        }
        guard identity == nil else { return }
        do {
            if let handle = try await encryption.resetIdentity() {
                try await handle.reset(auth: nil)
            }
        } catch {
            AccountStep.log.error("interno: cross-signing setup failed")
        }
    }

    private func add(_ member: AccountSession, work: AccountSession) {
        sessions[work.userId] = member
        let workId = work.userId
        member.onAuthError = { [weak self, weak member] isSoftLogout in
            guard let self, let member else { return }
            Task { await self.ended(member, workId: workId, isSoftLogout: isSoftLogout) }
        }
        NotificationActions.shared.attach(member)
        _ = chatList(for: member)
        if PushRegistration.shared.attachedUserId == workId {
            PushRegistration.shared.attachMember(member)
        }
    }

    // MARK: End

    /// Spec interno notes: the person left Quem atende, so bipe-api deactivated the member identity and its token stops
    /// working. The session goes from this phone without a prompt. A soft logout keeps the store and its device ID: the
    /// next open signs in again with a new password from bipe-api.
    private func ended(_ member: AccountSession, workId: String, isSoftLogout: Bool) async {
        guard sessions[workId] === member else { return }
        AccountStep.log.notice("interno: member session ended")
        if isSoftLogout {
            await remove(member, workId: workId)
            await member.pause()
            SessionStore.delete(userId: member.userId)
        } else {
            await signOut(forWork: workId, alreadyLoggedOut: true)
        }
    }

    /// The work account leaves this phone: its member session goes too, with its store.
    func signOut(forWork workId: String, alreadyLoggedOut: Bool = false) async {
        let memberId = sessions[workId]?.userId ?? Self.memberId(forWork: workId)
        Self.remember(nil, forWork: workId)
        guard let memberId else { return }
        if let member = sessions[workId] {
            await remove(member, workId: workId)
            await member.signOut(alreadyLoggedOut: alreadyLoggedOut)
        } else {
            SessionStore.delete(userId: memberId)
            AccountSession.deleteStore(userId: memberId)
        }
        NotificationKeys.accountRoutes?.remove(userId: memberId)
    }

    private func remove(_ member: AccountSession, workId: String) async {
        sessions[workId] = nil
        member.onAuthError = nil
        NotificationActions.shared.detach(userId: member.userId)
        await PushRegistration.shared.detachMember(member)
        listTasks.removeValue(forKey: member.userId)?.cancel()
        chatLists[member.userId] = nil
    }

    /// The app came back to the foreground: the member sessions sync again.
    func resume() async {
        for member in sessions.values {
            await member.syncService.start()
            await member.resumeSending()
        }
    }
}

/// Spec interno state and actions for one work account.
@MainActor
final class InternoModel: ObservableObject {
    enum Phase: Equatable {
        case setup
        case ready
        case failed
    }

    @Published private(set) var phase: Phase = .setup
    /// State people: the other members, from GET /v1/me/business/members, without me.
    @Published private(set) var people: [BipeWorkMember] = []
    @Published private(set) var geralRoomId: String?
    @Published private(set) var member: AccountSession?
    @Published private(set) var chatList: ChatListModel?
    /// A new chat is being opened or made.
    @Published private(set) var startingChat = false
    @Published var chatFailed = false

    let work: AccountSession
    private let api: BipeBusinessAPI
    private var opening = false
    private var ensuring = false

    /// A Geral room this phone made whose claim did not reach bipe-api: the next try claims it, not a new room.
    private var pendingGeralKey: String { "bipe.interno.pendingGeral.\(work.userId)" }

    init(work: AccountSession, api: BipeAPIClient) {
        self.work = work
        self.api = work.business(api)
        if let member = InternoSessions.shared.session(forWork: work.userId) {
            self.member = member
            chatList = InternoSessions.shared.chatList(for: member)
            phase = .ready
        }
    }

    /// Action open. A failure with a session already open keeps the rooms on screen.
    func open() async {
        guard !opening else { return }
        opening = true
        defer { opening = false }
        if member == nil { phase = .setup }
        do {
            let session = try await InternoSessions.shared.open(forWork: work, api: api)
            member = session
            chatList = InternoSessions.shared.chatList(for: session)
            phase = .ready
        } catch {
            if member == nil { phase = .failed }
            return
        }
        await refresh()
    }

    /// GET members, then the invites from other members and ensureGeral.
    func refresh() async {
        guard let member, let read = try? await api.members(), self.member === member else { return }
        apply(read, member: member)
        await acceptInvites(member)
        await ensureGeral(read, member: member)
    }

    private func apply(_ read: BipeWorkMembers, member: AccountSession) {
        people = read.members.filter { $0.userId != member.userId && $0.userId != read.me }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        geralRoomId = read.geralRoomId
    }

    /// The direct chats and Geral that other members invited me to. The homeserver lets only members of this account
    /// invite a member identity (ADR 0284 point 8); the app also checks that the inviter is one of the people.
    private func acceptInvites(_ member: AccountSession) async {
        let known = Set(people.map(\.userId))
        for room in member.client.rooms() where room.membership() == .invited {
            guard let inviter = try? await room.inviter(), known.contains(inviter.userId) else { continue }
            _ = try? await room.join()
        }
    }

    /// Action ensureGeral. With two or more members: make Geral when there is none, else invite the members that are
    /// not in it. Only a member already in Geral can invite.
    private func ensureGeral(_ read: BipeWorkMembers, member: AccountSession) async {
        guard !ensuring, read.members.count >= 2 else { return }
        ensuring = true
        defer { ensuring = false }
        let memberIds = read.members.map(\.userId)
        if let geral = read.geralRoomId {
            UserDefaults.standard.removeObject(forKey: pendingGeralKey)
            guard let room = await joined(geral, member: member) else { return }
            await inviteMissing(room, memberIds: memberIds, me: member.userId)
            return
        }
        let roomId: String
        if let pending = UserDefaults.standard.string(forKey: pendingGeralKey) {
            roomId = pending
        } else {
            let others = memberIds.filter { $0 != member.userId }
            do {
                roomId = try await member.client.createRoom(request: CreateRoomParameters(
                    name: BipeStrings.internoGeral, isEncrypted: true, isDirect: false,
                    visibility: .private, preset: .privateChat, invite: others))
            } catch {
                return
            }
            UserDefaults.standard.set(roomId, forKey: pendingGeralKey)
        }
        do throws(BipeAPIError) {
            let claimed = try await api.claimGeral(roomId: roomId)
            UserDefaults.standard.removeObject(forKey: pendingGeralKey)
            apply(claimed, member: member)
            if let room = try? member.client.getRoom(roomId: roomId) {
                await inviteMissing(room, memberIds: claimed.members.map(\.userId), me: member.userId)
            }
        } catch {
            guard case .server(.geralExists, _, _) = error else { return }
            // Another member's app claimed Geral first: leave this room, join that one.
            UserDefaults.standard.removeObject(forKey: pendingGeralKey)
            if let room = try? member.client.getRoom(roomId: roomId) { try? await room.leave() }
            guard let again = try? await api.members() else { return }
            apply(again, member: member)
            if let geral = again.geralRoomId { _ = await joined(geral, member: member) }
        }
    }

    /// The room once this session is in it: joins it when it holds an invite. nil when it can not.
    private func joined(_ roomId: String, member: AccountSession) async -> Room? {
        if let room = try? member.client.getRoom(roomId: roomId), room.membership() == .joined { return room }
        return try? await member.client.joinRoomById(roomId: roomId)
    }

    private func inviteMissing(_ room: Room, memberIds: [String], me: String) async {
        guard let members = await GroupPeople.members(of: room) else { return }
        let inside = Set(members.filter { $0.membership == .join || $0.membership == .invite }.map(\.userId))
        for userId in memberIds where userId != me && !inside.contains(userId) {
            try? await room.inviteUserById(userId: userId)
        }
    }

    /// Action newChat: the direct chat with that member, made when there is none (private, invite only, encrypted,
    /// is_direct). nil when it failed.
    func directChat(with userId: String) async -> String? {
        guard let member, people.contains(where: { $0.userId == userId }), !startingChat else { return nil }
        startingChat = true
        defer { startingChat = false }
        if let rooms = try? member.client.getDmRooms(userId: userId) {
            for room in rooms {
                switch room.membership() {
                case .joined: return room.id()
                case .invited: if (try? await room.join()) != nil { return room.id() }
                default: continue
                }
            }
        }
        do {
            return try await NativeDm.create(with: userId, client: member.client)
        } catch {
            chatFailed = true
            return nil
        }
    }

    /// The rooms of state rooms: Geral first, then direct chats newest first.
    func rooms(_ chats: [ChatSummary]) -> (geral: ChatSummary?, direct: [ChatSummary]) {
        let geral = geralRoomId.flatMap { id in chats.first { $0.roomId == id } }
        return (geral, chats.filter { $0.roomId != geralRoomId && $0.isDm })
    }
}

/// Spec interno: the row at the top of Conversas of a work account with two or more people. It shows the unread
/// count of the member session once it runs.
struct InternoRow: View {
    let workId: String
    @ObservedObject private var sessions = InternoSessions.shared

    var body: some View {
        if let list = sessions.chatList(forWork: workId) {
            InternoRowCount(list: list, workId: workId)
        } else {
            InternoRowLabel(workId: workId, count: nil)
        }
    }
}

private struct InternoRowCount: View {
    @ObservedObject var list: ChatListModel
    let workId: String

    var body: some View {
        let unread = list.chats.filter(\.isUnread).count
        InternoRowLabel(workId: workId, count: unread > 0 ? unread : nil)
    }
}

private struct InternoRowLabel: View {
    let workId: String
    let count: Int?

    var body: some View {
        ListEntryRow(icon: .chatCircle, title: BipeStrings.internoTitle,
                     detail: BipeStrings.internoRowBody(BipeAccountLabels.label(for: workId)), count: count)
    }
}

/// Spec interno: Geral, the direct chats and Nova conversa, on the member session.
struct InternoView: View {
    @StateObject private var model: InternoModel
    /// The room open in the normal chat timeline, on the member session.
    @State private var openRoom: String?
    @State private var picking = false
    private let initialRoomId: String?

    init(work: AccountSession, api: BipeAPIClient, initialRoomId: String? = nil) {
        _model = StateObject(wrappedValue: InternoModel(work: work, api: api))
        self.initialRoomId = initialRoomId
    }

    var body: some View {
        content
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.internoTitle)
            .accessibilityIdentifier(BipeScreen.interno.rawValue)
            .task {
                await model.open()
                if let initialRoomId, model.phase == .ready { openRoom = initialRoomId }
            }
            .refreshable { await model.refresh() }
            .sheet(isPresented: $picking) {
                InternoPickView(people: model.people) { userId in
                    picking = false
                    Task { if let roomId = await model.directChat(with: userId) { openRoom = roomId } }
                }
            }
            .navigationDestination(item: $openRoom) { roomId in
                timeline(roomId)
            }
            .alert(BipeStrings.errorGeneric, isPresented: $model.chatFailed) {
                Button(BipeStrings.appClose, role: .cancel) {}
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .setup:
            VStack(spacing: 12) {
                ProgressView()
                Text(BipeStrings.internoSetup).foregroundStyle(BipeColor.inkSecondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.internoError)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) { Task { await model.open() } }
                    .buttonStyle(.bipeProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready:
            if let list = model.chatList {
                InternoList(model: model, list: list, open: { openRoom = $0 }, newChat: { picking = true })
            }
        }
    }

    @ViewBuilder
    private func timeline(_ roomId: String) -> some View {
        if let member = model.member, let list = model.chatList {
            VStack(spacing: 0) {
                // Spec interno newChat: a direct chat says at the top that only the two people see it.
                if roomId != model.geralRoomId {
                    InternoPrivateHint()
                }
                ChatTimelineView(session: member, media: list.media, roomId: roomId)
                    .id(roomId)
            }
            .environment(\.openOtherChat, { openRoom = $0 })
        }
    }
}

private struct InternoList: View {
    @ObservedObject var model: InternoModel
    @ObservedObject var list: ChatListModel
    let open: (String) -> Void
    let newChat: () -> Void

    var body: some View {
        let rooms = model.rooms(list.chats(in: .todas))
        List {
            if model.people.isEmpty {
                Text(BipeStrings.internoEmpty)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if let geral = rooms.geral {
                Button { open(geral.roomId) } label: {
                    ListEntryRow(icon: .usersThree, title: BipeStrings.internoGeral,
                                 detail: BipeStrings.internoGeralBody(BipeAccountLabels.label(for: model.work.userId)),
                                 count: geral.unreadCount > 0 ? geral.unreadCount : nil)
                }
                .buttonStyle(.plain)
            }
            if !model.people.isEmpty {
                Button(action: newChat) {
                    ListEntryRow(icon: .plus, title: BipeStrings.internoNew)
                }
                .buttonStyle(.plain)
                .disabled(model.startingChat)
            }
            ForEach(rooms.direct) { chat in
                Button { open(chat.roomId) } label: { ChatRow(chat: chat, media: list.media) }
                    .buttonStyle(.plain)
            }
        }
        .listStyle(.plain)
    }
}

/// Spec interno newChat: bipe_interno_private_hint at the top of a direct chat.
private struct InternoPrivateHint: View {
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            PhosphorIcon.lockSimple.image
                .resizable()
                .frame(width: 18, height: 18)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(BipeStrings.internoPrivateHint)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(BipeColor.accentWash)
        .accessibilityElement(children: .combine)
    }
}

/// Spec interno newChat: the picker of the other members (bipe_interno_pick_title).
private struct InternoPickView: View {
    let people: [BipeWorkMember]
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(people) { person in
                Button { pick(person.userId) } label: {
                    Text(person.name)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
            .navigationTitle(BipeStrings.internoPickTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
    }
}
