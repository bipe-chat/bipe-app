// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
import SwiftUI
import UIKit

/// Type AccountSummary of spec app-tabs.
struct AccountSummary: Identifiable, Equatable {
    var userId: String
    var displayName: String
    var handle: String?
    var avatarUrl: String?
    var isCurrent: Bool
    var hasUnread: Bool
    var isCompany = false
    /// For a company account made on this phone: its owner's name, while the owner is on this phone (bipe-chat/bipe#444).
    var ownerName: String?
    var ownerId: String?
    /// true once the profile (name, photo, @) has loaded.
    var isLoaded = false

    var id: String { userId }

    /// The @handle line under the name; none when the name already is the @handle.
    var handleLine: String? {
        handle.map { "@\($0)" }.flatMap { $0 == displayName ? nil : $0 }
    }
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
        /// Company accounts only, in place of Grupos: the business tools (spec app-tabs, business-tools).
        case trabalho
        case voce
        case search
    }

    @Published var selectedTab: Tab = .conversas
    @Published private(set) var accounts: [AccountSummary] = []
    @Published private(set) var conversasUnread = 0
    @Published private(set) var gruposAttention = 0
    @Published private(set) var otherAccountUnread = false
    /// The current account's photo or initial for the Você tab (ADR 0132 shape); nil before the accounts load.
    @Published private(set) var voceIcon: UIImage?
    /// The current account is a company account: Trabalho replaces Grupos and Conversas holds the groups.
    @Published private(set) var currentIsCompany = false
    @Published private(set) var canAddAccount = true
    /// max_accounts of the account on screen, for bipe_account_cap_reached (spec account-new).
    @Published private(set) var maxPersonalAccounts = BipeRegistries.maxPersonalAccounts

    let start: AccountStartModel
    private var chatLists: [String: ChatListModel] = [:]
    private var inviteModels: [String: InvitesModel] = [:]
    private var latestChats: [String: [ChatSummary]] = [:]
    private var sessionIds: [String] = []
    private var currentId: String?
    private struct Profile {
        var displayName: String
        var avatarUrl: String?
        var handle: String?
        var isCompany: Bool
        var maxAccounts: Int
        /// The bipe-api part (@handle, company, account limit) has loaded too.
        var isLoaded = false
    }
    private var profiles: [String: Profile] = [:]
    private var avatarPhotos: [String: UIImage] = [:]
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
        _ = invites(for: session)
        let userId = session.userId
        listCancellables[userId] = model.$chats
            .receive(on: RunLoop.main)
            .sink { [weak self] chats in
                self?.latestChats[userId] = chats
                self?.refresh()
                Task { await self?.inviteModels[userId]?.retryNotes() }
            }
        Task { await model.start() }
        Task { await loadProfile(of: session) }
        return model
    }

    /// Action selectTab. A tap on Você always opens Você.
    /// A company account has no Grupos tab and a personal account no Trabalho tab: those go to Conversas.
    func selectTab(_ tab: Tab) {
        selectedTab = Self.available(tab, isCompany: currentIsCompany)
    }

    private static func available(_ tab: Tab, isCompany: Bool) -> Tab {
        switch tab {
        case .grupos where isCompany, .trabalho where !isCompany: .conversas
        default: tab
        }
    }

    /// Action doubleTapVoce: the next account in accounts, wrapping around, with the switch haptic (ADR 0134).
    /// With one account it does the same as a tap.
    func doubleTapVoce() {
        selectTab(.voce)
        guard accounts.count > 1, let index = accounts.firstIndex(where: \.isCurrent) else { return }
        switchAccount(to: accounts[(index + 1) % accounts.count].userId)
    }

    /// Action switchAccount: that account's chats show in the tabs, and the choice is saved for the next start.
    func switchAccount(to userId: String) {
        guard userId != currentId, accounts.contains(where: { $0.userId == userId }) else { return }
        start.switchAccount(to: userId)
        BipeHaptic.tick.play()
    }

    func media(for userId: String) -> MediaLoader? { chatLists[userId]?.media }

    /// Refresh photos, handles and the live personal-account limit after profile changes or returning to the app.
    func refreshProfiles() async {
        for session in start.sessions {
            await loadProfile(of: session)
            await invites(for: session).refresh()
        }
    }

    func invites(for session: AccountSession) -> InvitesModel {
        if let existing = inviteModels[session.userId] { return existing }
        let model = InvitesModel(session: session, api: start.api)
        inviteModels[session.userId] = model
        model.start()
        return model
    }

    private func sessionsChanged(_ sessions: [AccountSession], current: String?) {
        sessionIds = sessions.map(\.userId)
        currentId = current
        // A session replaced after soft-logout (same account, new client) gets new models too.
        let byId = Dictionary(uniqueKeysWithValues: sessions.map { ($0.userId, $0) })
        for userId in chatLists.keys where byId[userId] == nil || chatLists[userId]?.session !== byId[userId] {
            chatLists[userId] = nil
            listCancellables[userId] = nil
            latestChats[userId] = nil
            profiles[userId] = nil
            avatarPhotos[userId] = nil
            inviteModels.removeValue(forKey: userId)?.close()
        }
        for session in sessions {
            _ = chatList(for: session)
        }
        refresh()
    }

    /// Name and photo come first, from the homeserver, so the account menu names each account while the bipe-api calls
    /// run. A call that fails keeps the value the account had.
    private func loadProfile(of session: AccountSession) async {
        let userId = session.userId
        var profile = profiles[userId] ?? Profile(displayName: "", isCompany: BipeAccountLabels.isCompany(userId),
                                                  maxAccounts: BipeRegistries.maxPersonalAccounts)
        if let displayName = try? await session.client.displayName(), !displayName.isEmpty {
            profile.displayName = displayName
            if displayName != Self.localpart(of: userId) {
                BipeAccountLabels.remember(displayName: displayName, for: userId)
            }
        }
        do {
            profile.avatarUrl = try await session.client.avatarUrl()
        } catch {}
        guard sessionIds.contains(userId), let media = chatLists[userId]?.media else { return }
        if let avatarUrl = profile.avatarUrl, let photo = await media.thumbnail(mxcUrl: avatarUrl, size: 84) {
            avatarPhotos[userId] = photo
        } else if profile.avatarUrl == nil {
            avatarPhotos[userId] = nil
        }
        guard sessionIds.contains(userId) else { return }
        profiles[userId] = profile
        refresh()

        await chatLists[userId]?.refreshNotes()
        let api = start.api
        let mine = try? await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeMyHandle in
            try await api.myHandle(openID: token)
        }
        let status = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }
        let company = await media.isCompany(userId: userId)
        guard sessionIds.contains(userId), var loaded = profiles[userId] else { return }
        if let mine { loaded.handle = mine.handle }
        if let status { loaded.maxAccounts = max(1, status.maxAccounts ?? loaded.maxAccounts) }
        loaded.isCompany = company
        loaded.isLoaded = true
        profiles[userId] = loaded
        refresh()
    }

    /// Spec account-switcher: the display name, else the @handle, else the remembered name; never the Matrix ID.
    /// Only Profile edit sets a display name, and Synapse starts one as the Matrix ID's localpart.
    private static func title(of profile: Profile?, userId: String) -> String {
        if let name = profile?.displayName, !name.isEmpty, name != localpart(of: userId) {
            return name
        }
        if let handle = profile?.handle, !handle.isEmpty {
            return "@\(handle)"
        }
        return AccountStartModel.name(of: userId)
    }

    private static func localpart(of userId: String) -> String? {
        userId.dropFirst().split(separator: ":").first.map(String.init)
    }

    /// The account's photo, else its initial on the accent wash: a circle for a person, a rounded square for a company
    /// (ADR 0132). Drawn as is, so the tab bar does not tint it.
    private func tabIcon(of account: AccountSummary) -> UIImage {
        let side: CGFloat = 28
        let bounds = CGRect(x: 0, y: 0, width: side, height: side)
        let image = UIGraphicsImageRenderer(size: bounds.size).image { _ in
            let shape = account.isCompany
                ? UIBezierPath(roundedRect: bounds, cornerRadius: side * 0.25)
                : UIBezierPath(ovalIn: bounds)
            shape.addClip()
            if let photo = avatarPhotos[account.userId] {
                photo.draw(in: bounds)
            } else {
                UIColor(BipeColor.accentWash).setFill()
                UIRectFill(bounds)
                let initial = account.displayName.trimmingCharacters(in: CharacterSet(charactersIn: "@#!+ ")).first
                    .map { String($0).uppercased() } ?? ""
                let text = NSAttributedString(string: initial, attributes: [
                    .font: UIFont.systemFont(ofSize: side * 0.46, weight: .semibold),
                    .foregroundColor: UIColor(BipeColor.accentInk),
                ])
                let size = text.size()
                text.draw(at: CGPoint(x: (side - size.width) / 2, y: (side - size.height) / 2))
            }
        }
        return image.withRenderingMode(.alwaysOriginal)
    }

    /// Spec account-switcher notes: each owner with its company accounts right after it; a company whose owner is not
    /// on this phone keeps its own place.
    static func grouped(_ summaries: [AccountSummary]) -> [AccountSummary] {
        var withOwners = summaries.map { summary in
            var summary = summary
            if let owner = AccountList.owner(of: summary.userId),
               let ownerSummary = summaries.first(where: { $0.userId == owner && !$0.isCompany }) {
                summary.ownerId = owner
                summary.ownerName = ownerSummary.displayName
            }
            return summary
        }
        var result: [AccountSummary] = []
        for summary in withOwners where summary.ownerId == nil {
            result.append(summary)
            result += withOwners.filter { $0.ownerId == summary.userId }
        }
        withOwners.removeAll { summary in result.contains { $0.userId == summary.userId } }
        return result + withOwners
    }

    private func refresh() {
        let summaries = sessionIds.map { userId in
            let profile = profiles[userId]
            return AccountSummary(userId: userId,
                                  displayName: Self.title(of: profile, userId: userId),
                                  handle: profile?.handle,
                                  avatarUrl: profile?.avatarUrl,
                                  isCurrent: userId == currentId,
                                  hasUnread: latestChats[userId]?.contains { $0.isUnread } ?? false,
                                  isCompany: profile?.isCompany ?? false,
                                  isLoaded: profile?.isLoaded ?? false)
        }
        accounts = Self.grouped(summaries)
        let chats = currentId.flatMap { latestChats[$0] } ?? []
        conversasUnread = chats.filter { $0.scope == .conversas && $0.isUnread }.count
        gruposAttention = chats.filter { $0.scope == .grupos }.reduce(0) { $0 + $1.mentionCount }
        otherAccountUnread = accounts.contains { !$0.isCurrent && $0.hasUnread }
        voceIcon = accounts.first(where: \.isCurrent).map(tabIcon(of:))
        currentIsCompany = currentId.flatMap { profiles[$0]?.isCompany } ?? false
        let tab = Self.available(selectedTab, isCompany: currentIsCompany)
        if tab != selectedTab { selectedTab = tab }
        let limit = currentId.flatMap { profiles[$0]?.maxAccounts } ?? BipeRegistries.maxPersonalAccounts
        maxPersonalAccounts = limit
        canAddAccount = accounts.filter { !$0.isCompany }.count < limit
    }
}
