// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation

/// Type AccountSummary of spec app-tabs.
struct AccountSummary: Identifiable, Equatable {
    var userId: String
    var displayName: String
    var handle: String?
    var avatarUrl: String?
    var isCurrent: Bool
    var hasUnread: Bool
    var isCompany = false

    var id: String { userId }
}

/// Spec app-tabs (bipe-shared product/screens/app-tabs.json).
///
/// Keeps one chat list per account for the life of the app, so the badges and the other-account dot follow every
/// account while only one shows.
@MainActor
final class AppTabsModel: ObservableObject {
    enum Tab: String, Hashable {
        case conversas
        case grupos
        case voce
        case search
    }

    @Published var selectedTab: Tab = .conversas
    @Published private(set) var accounts: [AccountSummary] = []
    @Published private(set) var conversasUnread = 0
    @Published private(set) var gruposAttention = 0
    @Published private(set) var otherAccountUnread = false
    @Published var showsAccountSwitcher = false
    @Published private(set) var canAddAccount = true

    let start: AccountStartModel
    private var chatLists: [String: ChatListModel] = [:]
    private var latestChats: [String: [ChatSummary]] = [:]
    private var sessionIds: [String] = []
    private var currentId: String?
    private struct Profile {
        var displayName: String
        var avatarUrl: String?
        var handle: String?
        var isCompany: Bool
        var maxAccounts: Int
    }
    private var profiles: [String: Profile] = [:]
    private var cancellables: Set<AnyCancellable> = []
    private var listCancellables: [String: AnyCancellable] = [:]

    init(start: AccountStartModel) {
        self.start = start
        start.$sessions
            .combineLatest(start.$userId)
            .receive(on: RunLoop.main)
            .sink { [weak self] sessions, userId in self?.sessionsChanged(sessions, current: userId) }
            .store(in: &cancellables)
    }

    /// The chat list of an account, started on first use.
    func chatList(for session: AccountSession) -> ChatListModel {
        if let model = chatLists[session.userId] {
            return model
        }
        let model = ChatListModel(session: session)
        chatLists[session.userId] = model
        let userId = session.userId
        listCancellables[userId] = model.$chats
            .receive(on: RunLoop.main)
            .sink { [weak self] chats in
                self?.latestChats[userId] = chats
                self?.refresh()
            }
        Task { await model.start() }
        Task { await loadProfile(of: session) }
        return model
    }

    /// Action selectTab. A tap on Você always opens Você.
    func selectTab(_ tab: Tab) {
        selectedTab = tab
    }

    /// Action doubleTapVoce: the next account in accounts, wrapping around, with the switch haptic (ADR 0134).
    /// With one account it does the same as a tap.
    func doubleTapVoce() {
        selectTab(.voce)
        guard accounts.count > 1, let index = accounts.firstIndex(where: \.isCurrent) else { return }
        switchAccount(to: accounts[(index + 1) % accounts.count].userId)
    }

    /// Action longPressVoce: opens the account menu without changing the visible tab.
    func longPressVoce() {
        showsAccountSwitcher = true
    }

    /// Action switchAccount: that account's chats show in the tabs, and the choice is saved for the next start.
    func switchAccount(to userId: String) {
        showsAccountSwitcher = false
        guard userId != currentId, accounts.contains(where: { $0.userId == userId }) else { return }
        start.switchAccount(to: userId)
        BipeHaptic.tick.play()
    }

    func media(for userId: String) -> MediaLoader? { chatLists[userId]?.media }

    /// Refresh photos, handles and the live personal-account limit after profile changes or returning to the app.
    func refreshProfiles() async {
        for session in start.sessions { await loadProfile(of: session) }
    }

    private func sessionsChanged(_ sessions: [AccountSession], current: String?) {
        sessionIds = sessions.map(\.userId)
        currentId = current
        for userId in chatLists.keys where !sessionIds.contains(userId) {
            chatLists[userId] = nil
            listCancellables[userId] = nil
            latestChats[userId] = nil
            profiles[userId] = nil
        }
        for session in sessions {
            _ = chatList(for: session)
        }
        refresh()
    }

    private func loadProfile(of session: AccountSession) async {
        let displayName = (try? await session.client.displayName()) ?? ""
        let avatarUrl = try? await session.client.avatarUrl()
        let api = start.api
        let mine = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeMyHandle in
            try await api.myHandle(accessToken: token)
        }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }
        guard sessionIds.contains(session.userId), let media = chatLists[session.userId]?.media else { return }
        let company = await media.isCompany(userId: session.userId)
        guard sessionIds.contains(session.userId) else { return }
        profiles[session.userId] = Profile(displayName: displayName, avatarUrl: avatarUrl, handle: mine?.handle,
                                          isCompany: company, maxAccounts: max(1, status?.maxAccounts ?? BipeRegistries.maxPersonalAccounts))
        refresh()
    }

    private func refresh() {
        accounts = sessionIds.map { userId in
            let profile = profiles[userId]
            return AccountSummary(userId: userId,
                                  displayName: profile?.displayName.isEmpty == false ? profile!.displayName : BipeStrings.tabYou,
                                  handle: profile?.handle,
                                  avatarUrl: profile?.avatarUrl,
                                  isCurrent: userId == currentId,
                                  hasUnread: latestChats[userId]?.contains { $0.unreadCount > 0 } ?? false,
                                  isCompany: profile?.isCompany ?? false)
        }
        let chats = currentId.flatMap { latestChats[$0] } ?? []
        conversasUnread = chats.filter { $0.scope == .conversas && $0.unreadCount > 0 }.count
        gruposAttention = chats.filter { $0.scope == .grupos }.reduce(0) { $0 + $1.mentionCount }
        otherAccountUnread = accounts.contains { !$0.isCurrent && $0.hasUnread }
        let limit = currentId.flatMap { profiles[$0]?.maxAccounts } ?? BipeRegistries.maxPersonalAccounts
        canAddAccount = accounts.filter { !$0.isCompany }.count < limit
    }
}
