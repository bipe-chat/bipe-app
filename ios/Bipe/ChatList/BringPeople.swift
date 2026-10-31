// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Specs bring-people and bring-people-reminder (bipe-chat/bipe#752): up to 4 named personal invites, then at most one
/// reminder a week that names one person. One model per account.
@MainActor
final class BringPeopleModel: ObservableObject {
    enum Status: Equatable { case empty, sent, joined, expired }

    struct Slot: Identifiable, Equatable {
        let index: Int
        let inviteId: String?
        let name: String?
        let status: Status
        let joinedUserId: String?
        var id: Int { index }
    }

    enum Phase: Equatable {
        case loading
        case ready
        /// One slot's invite is being made.
        case creating(Int)
        /// bipe_error_network or bipe_error_invite_quota.
        case failed(String)
    }

    struct Reminder: Equatable {
        let index: Int
        let name: String
        let kind: BipeBringPeople.Kind
    }

    @Published private(set) var slots: [Slot] = []
    @Published private(set) var available: Int?
    @Published private(set) var nextAvailableAt: Date?
    @Published private(set) var phase: Phase = .loading
    @Published private(set) var reminder: Reminder?
    /// The slot whose name field is open. One slot edits at a time.
    @Published var editing: Int?

    let session: AccountSession
    private let api: BipeAPIClient
    let memory: BipeBringPeopleMemory
    private let promptSlot: BipePromptSlot
    private var invites: [String: BipeInvite] = [:]
    private var links: [String: URL] = [:]
    /// The result of POST for a slot, so a retry repeats only the name and never makes a second invite.
    private var created: (index: Int, invite: BipeCreatedInvite)?

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        memory = BipeBringPeopleMemory(userID: session.userId)
        promptSlot = BipePromptSlot(userID: session.userId)
    }

    /// The slots the sheet shows: filled ones, then the empty ones the invite quota allows.
    var visibleSlots: [Slot] {
        let filled = slots.filter { $0.status != .empty }
        let empty = slots.filter { $0.status == .empty }.prefix(max(available ?? 0, 0))
        return filled + empty
    }

    var anyFilled: Bool { slots.contains { $0.status != .empty } }

    /// Action appear: GET /v1/me/invites, then each slot's status from its invite.
    func load() async {
        if slots.isEmpty { phase = .loading }
        do {
            let quota = try await withToken { (token: String) async throws(BipeAPIError) -> BipeMyInvites in
                try await self.api.myInvites(accessToken: token)
            }
            available = quota.available
            nextAvailableAt = quota.nextAvailableAt
            invites = Dictionary(quota.invites.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            memory.keep(Set(quota.invites.filter { $0.status != .revoked }.map(\.id)))
            let userId = session.userId
            links = await Task.detached(priority: .utility) { BipeInviteStore(userID: userId).links() }.value
            rebuild()
            if case .creating = phase {} else { phase = .ready }
        } catch {
            if slots.isEmpty { rebuild() }
            phase = .failed(BipeStrings.errorNetwork)
        }
    }

    private func rebuild() {
        let ids = memory.inviteIDs
        slots = (0..<BipeOnboarding.bringPeopleSlots).map { index in
            guard index < ids.count, let invite = invites[ids[index]] else {
                return Slot(index: index, inviteId: nil, name: nil, status: .empty, joinedUserId: nil)
            }
            let status: Status = switch invite.status {
            case .pending: .sent
            case .used: .joined
            case .expired, .revoked: .expired
            }
            return Slot(index: index, inviteId: invite.id, name: invite.name, status: status, joinedUserId: invite.joinedUserId)
        }
        refreshReminder()
    }

    /// Actions invite and replaceExpired: POST /v1/me/invites, then PUT the name. Returns the link to share.
    func invite(at index: Int, name: String) async -> URL? {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        phase = .creating(index)
        do {
            let made: BipeCreatedInvite
            if let created, created.index == index {
                made = created.invite
            } else {
                made = try await withToken { (token: String) async throws(BipeAPIError) -> BipeCreatedInvite in
                    try await self.api.createInvite(accessToken: token)
                }
                created = (index, made)
            }
            let userId = session.userId
            _ = await Task.detached(priority: .utility) { BipeInviteStore(userID: userId).saveLink(made.url, forInviteID: made.id) }.value
            memory.setInvite(made.id, at: index)
            try await withToken { (token: String) async throws(BipeAPIError) -> Void in
                try await self.api.nameInvite(id: made.id, name: trimmed, accessToken: token)
            }
            created = nil
            editing = nil
            phase = .ready
            await load()
            return made.url
        } catch {
            if case .server(.inviteQuotaExhausted, _, _)? = error as? BipeAPIError {
                phase = .failed(BipeStrings.errorInviteQuota)
            } else {
                phase = .failed(BipeStrings.errorNetwork)
            }
            return nil
        }
    }

    /// Action resend: the link kept on this phone, or nil when the invite was made on another phone.
    func link(at index: Int) -> URL? {
        slots.first { $0.index == index }?.inviteId.flatMap { links[$0] }
    }

    /// Action openJoined: the DM with the person who joined.
    func dmRoomId(at index: Int) -> String? {
        guard let userId = slots.first(where: { $0.index == index })?.joinedUserId,
              let room = try? session.client.getDmRoom(userId: userId) else { return nil }
        return room.id()
    }

    func joinedUserId(at index: Int) -> String? { slots.first { $0.index == index }?.joinedUserId }

    // MARK: - The sheet

    /// The sheet showed. The first time it counts as the first ask.
    func sheetShown() { memory.asked(sheet: true) }

    /// Action notNow: one decline. Shown only while no slot is filled.
    func notNow() {
        memory.decline()
        refreshReminder()
    }

    // MARK: - The reminder

    /// Trigger bringPeopleDue (BipeBringPeople.reminder) and the prompt slot.
    func refreshReminder() {
        let filled = slots.filter { $0.inviteId != nil }
        let ruleSlots = filled.map { slot in
            BipeBringPeople.RuleSlot(status: slot.inviteId.flatMap { invites[$0]?.status.rawValue } ?? "revoked",
                                     sentAt: slot.inviteId.flatMap { invites[$0]?.createdAt } ?? .distantFuture)
        }
        guard promptSlot.allows(.bringPeopleReminder),
              let next = BipeBringPeople.reminder(declines: memory.declines, lastAskAt: memory.lastAskAt, slots: ruleSlots, now: Date()),
              let name = filled[next.slot].name, !name.isEmpty else {
            reminder = nil
            return
        }
        reminder = Reminder(index: filled[next.slot].index, name: name, kind: next.kind)
    }

    func reminderShown() { promptSlot.didShow(.bringPeopleReminder) }

    /// Action act: pending gives the kept link (nil: the invite is on another phone); expired makes a new invite.
    func act() async -> URL? {
        guard let reminder else { return nil }
        memory.asked()
        self.reminder = nil
        switch reminder.kind {
        case .pending: return link(at: reminder.index)
        case .expired: return await invite(at: reminder.index, name: reminder.name)
        }
    }

    /// Action notNow of the reminder: one decline.
    func declineReminder() {
        memory.decline()
        reminder = nil
    }

    private func withToken<T>(_ operation: @MainActor (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, operation)
    }
}

/// Spec bring-people: "Quem da sua família devia estar aqui?" with up to 4 slots.
struct BringPeopleView: View {
    @ObservedObject var model: BringPeopleModel
    let openChat: (String) -> Void
    let openPerson: (String) -> Void
    let openInvites: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var shareURL: URL?
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(BipeStrings.bringPeopleTitle)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.bringPeopleBody)
                        .foregroundStyle(BipeColor.inkSecondary)
                    if model.phase == .loading && model.slots.isEmpty {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                    ForEach(model.visibleSlots) { slot in
                        slotRow(slot)
                    }
                    // State available 0: the empty slots stay hidden and the sheet says why.
                    if model.available == 0 && model.slots.contains(where: { $0.status == .empty }) {
                        Text(BipeInviteQuota.noneLeft(nextAvailableAt: model.nextAvailableAt))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    if case .failed(let message) = model.phase {
                        Text(message).font(.footnote).foregroundStyle(BipeColor.trustDangerInk)
                        Button(BipeStrings.appRetry) { Task { await retry() } }
                    }
                    VStack(spacing: 12) {
                        Button { dismiss() } label: {
                            Text(BipeStrings.bringPeopleDone).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bipeProminent)
                        if !model.anyFilled {
                            Button {
                                model.notNow()
                                dismiss()
                            } label: {
                                Text(BipeStrings.fallbackSkip).frame(maxWidth: .infinity)
                            }
                        }
                    }
                    .padding(.top, 8)
                }
                .padding(24)
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.bringPeople.rawValue)
        .task { await model.load() }
        .sheet(item: Binding(get: { shareURL.map(ShareLinkItem.init) }, set: { shareURL = $0?.url })) { item in
            ShareSheet(items: [BipeStrings.invitesShareMessage(item.url.absoluteString)]).ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func slotRow(_ slot: BringPeopleModel.Slot) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.phase == .creating(slot.index) {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(BipeStrings.appInviteCreating).foregroundStyle(BipeColor.inkSecondary)
                }
            } else {
                switch slot.status {
                case .empty where model.editing == slot.index:
                    TextField(BipeStrings.bringPeopleNamePlaceholder, text: $name)
                        .textContentType(.name)
                        .submitLabel(.send)
                        .focused($nameFocused)
                        .onSubmit { Task { await invite(slot.index) } }
                        .padding(12)
                        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                    Button { Task { await invite(slot.index) } } label: {
                        Text(BipeStrings.firstCardInviteAction).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeProminent)
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                case .empty:
                    Button {
                        name = ""
                        model.editing = slot.index
                        nameFocused = true
                    } label: {
                        Text(BipeStrings.bringPeopleSlotEmpty).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .disabled(model.editing != nil)
                case .sent:
                    filledHeader(slot, state: BipeStrings.bringPeopleSent)
                    Button(BipeStrings.bringPeopleResend) { resend(slot.index) }
                case .joined:
                    Button { openJoined(slot.index) } label: {
                        filledHeader(slot, state: BipeStrings.bringPeopleJoined)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .buttonStyle(.plain)
                case .expired:
                    filledHeader(slot, state: BipeStrings.bringPeopleExpired)
                    Button(BipeStrings.bringPeopleExpiredAction) {
                        Task { await replace(slot) }
                    }
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
    }

    private func filledHeader(_ slot: BringPeopleModel.Slot, state: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if let name = slot.name, !name.isEmpty {
                Text(name).font(.headline).foregroundStyle(BipeColor.inkPrimary)
            }
            Text(state)
                .font(.subheadline)
                .foregroundStyle(slot.status == .joined ? BipeColor.statusSuccess : BipeColor.inkSecondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func invite(_ index: Int) async {
        guard let url = await model.invite(at: index, name: name) else { return }
        name = ""
        shareURL = url
    }

    private func replace(_ slot: BringPeopleModel.Slot) async {
        guard let name = slot.name, let url = await model.invite(at: slot.index, name: name) else { return }
        shareURL = url
    }

    private func resend(_ index: Int) {
        if let url = model.link(at: index) {
            shareURL = url
        } else {
            dismiss()
            openInvites()
        }
    }

    private func openJoined(_ index: Int) {
        if let roomId = model.dmRoomId(at: index) {
            dismiss()
            openChat(roomId)
        } else if let userId = model.joinedUserId(at: index) {
            dismiss()
            openPerson(userId)
        }
    }

    /// A failed POST retries the whole invite; a failed name retries only PUT (the model keeps the invite).
    private func retry() async {
        if let editing = model.editing, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            await invite(editing)
        } else {
            await model.load()
        }
    }
}

/// Spec bring-people-reminder: the card above Conversas that names one person.
struct BringPeopleReminderCard: View {
    @ObservedObject var model: BringPeopleModel
    let reminder: BringPeopleModel.Reminder
    let share: (URL) -> Void
    let openInvites: () -> Void

    var body: some View {
        let prompt = BipeOnboardingPrompt.bringPeopleReminder
        PromptCard(title: BipeStrings.bringPeopleReminderTitle(reminder.name),
                   body: reminder.kind == .pending ? BipeStrings.bringPeopleReminderPending : BipeStrings.bringPeopleReminderExpired,
                   primary: .init(title: reminder.kind == .pending ? BipeStrings.bringPeopleResend : BipeStrings.bringPeopleExpiredAction) {
                       let kind = reminder.kind
                       Task {
                           if let url = await model.act() {
                               share(url)
                           } else if kind == .pending {
                               openInvites()
                           }
                       }
                   },
                   texts: prompt.text.map { .init(title: $0.title, run: { model.declineReminder() }) })
        .accessibilityIdentifier(BipeScreen.bringPeopleReminder.rawValue)
        .onAppear { model.reminderShown() }
    }
}

private struct ShareLinkItem: Identifiable {
    let url: URL
    var id: URL { url }
}
