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

    let start: AccountStartModel
    private var chatLists: [String: ChatListModel] = [:]
    private var latestChats: [String: [ChatSummary]] = [:]
    private var sessionIds: [String] = []
    private var currentId: String?
    private var profiles: [String: (displayName: String, avatarUrl: String?)] = [:]
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

    /// Action longPressVoce: opens account-switcher. Until the menu is built (bipe-chat/bipe#577, second step) it opens Você.
    func longPressVoce() {
        selectTab(.voce)
    }

    /// Action switchAccount: that account's chats show in the tabs, and the choice is saved for the next start.
    func switchAccount(to userId: String) {
        guard userId != currentId else { return }
        start.switchAccount(to: userId)
        BipeHaptic.tick.play()
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
        profiles[session.userId] = (displayName, avatarUrl)
        refresh()
    }

    private func refresh() {
        accounts = sessionIds.map { userId in
            let profile = profiles[userId]
            return AccountSummary(userId: userId,
                                  displayName: profile?.displayName ?? "",
                                  handle: nil,
                                  avatarUrl: profile?.avatarUrl,
                                  isCurrent: userId == currentId,
                                  hasUnread: latestChats[userId]?.contains { $0.unreadCount > 0 } ?? false)
        }
        let chats = currentId.flatMap { latestChats[$0] } ?? []
        conversasUnread = chats.filter { $0.scope == .conversas && $0.unreadCount > 0 }.count
        gruposAttention = chats.filter { $0.scope == .grupos }.reduce(0) { $0 + $1.mentionCount }
        otherAccountUnread = accounts.contains { !$0.isCurrent && $0.hasUnread }
    }
}
