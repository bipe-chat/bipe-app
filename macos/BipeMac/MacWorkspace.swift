// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import Combine
import Foundation
import MatrixRustSDK

/// Each account owns its SDK client, sync and encrypted store. Switching changes only the visible account.
@MainActor
final class MacWorkspace: ObservableObject {
    @Published private(set) var accounts: [MacAccount] = []
    @Published private(set) var current: MacAccount?
    @Published private(set) var linking: MacAccount?
    @Published private(set) var failed = false
    private let vault: MacSessionVault
    private let defaults: UserDefaults
    private var launched = false
    private var accountChanges: [ObjectIdentifier: AnyCancellable] = [:]
    private var wakeObserver: NSObjectProtocol?
    /// Opens the main window again after the last window closed. The root view supplies it.
    var reopenWindow: (() -> Void)?
    var otherAccountUnread: Bool { accounts.contains { $0 !== current && $0.hasUnread } }
    private static let selectionKey = "bipe.mac.currentAccount"

    init(vault: MacSessionVault = MacSessionVault(keychain: SystemMacKeychain()), defaults: UserDefaults = .standard) {
        self.vault = vault
        self.defaults = defaults
    }

    func start() {
        guard !launched else { return }
        launched = true
        MacNotifications.shared.onOpen = { [weak self] route in self?.open(route) ?? false }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.accounts.forEach { $0.resumeSync() } }
        }
        restore()
        MacNotifications.shared.deliverPendingOpen()
    }

    func restore() {
        do {
            let records = try vault.loadAll()
            failed = false
            accounts = records.map { makeAccount(record: $0) }
            current = accounts.first { $0.userId == defaults.string(forKey: Self.selectionKey) } ?? accounts.first
            for account in accounts { account.start() }
            if accounts.isEmpty { addAccount() }
        } catch {
            // Never replace an unreadable installed session with a fresh login.
            failed = true
        }
    }

    private func makeAccount(record: MacSessionVault.Record? = nil) -> MacAccount {
        let account = MacAccount(record: record, vault: vault)
        accountChanges[ObjectIdentifier(account)] = account.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        account.roomList.onRows = { [weak self, weak account] rows in
            guard let self, let account else { return }
            MacNotifications.shared.update(account: account, rows: rows) { [weak self, weak account] room in
                guard let self, let account else { return false }
                return self.isOpenInKeyWindow(room, account: account)
            }
            MacNotifications.setBadge(self.accounts.map { MacTabAttention($0.roomRows.map(\.attention)) })
        }
        account.requests.onRequests = { [weak account] requests in
            guard let account else { return }
            MacNotifications.shared.update(account: account, requests: requests)
        }
        account.onReady = { [weak self, weak account] in
            guard let self, let account else { return }
            MacNotifications.shared.requestAuthorization()
            if self.linking === account {
                // Linking a revoked account again takes over its slot.
                if let index = self.accounts.firstIndex(where: { $0.userId == account.userId }) {
                    self.accounts[index] = account
                } else {
                    self.accounts.append(account)
                }
                self.linking = nil
                self.select(account)
            } else {
                if self.current === account { self.select(account) }
                self.objectWillChange.send()
            }
        }
        account.onAlreadyLinked = { [weak self, weak account] userID in
            guard let self, let account, let existing = self.accounts.first(where: { $0 !== account && $0.userId == userID }) else { return }
            account.cancelLink()
            if self.linking === account { self.linking = nil }
            self.select(existing)
        }
        return account
    }

    func select(_ account: MacAccount) {
        guard accounts.contains(where: { $0 === account }) else { return }
        current = account
        defaults.set(account.userId, forKey: Self.selectionKey)
        if let client = account.client { SyncedSettings.attach(client: client, userId: account.userId) }
    }

    /// Spec sign-out: this account only. The next account opens, or linking starts when none is left.
    func signOut(_ account: MacAccount) async throws {
        try await account.signOut()
        accounts.removeAll { $0 === account }
        accountChanges[ObjectIdentifier(account)] = nil
        if current === account {
            current = nil
            defaults.removeObject(forKey: Self.selectionKey)
            if let next = accounts.first { select(next) }
        }
        if accounts.isEmpty { addAccount() }
    }

    /// Spec notification-service, Mac: an open chat in the key window of the active app has no notification.
    private func isOpenInKeyWindow(_ room: String, account: MacAccount) -> Bool {
        NSApp.isActive && NSApp.keyWindow != nil && current === account && !account.showingRequests
            && account.selectedRooms[account.destination] == room
    }

    /// A tap selects the notification's own account. An unknown owner opens nothing. False keeps the route for restore.
    private func open(_ route: MacNotificationRoute) -> Bool {
        NSApp.activate()
        if !NSApp.windows.contains(where: { $0.isVisible && $0.canBecomeMain }) { reopenWindow?() }
        guard launched, !failed else { return false }
        guard let account = accounts.first(where: { $0.userId == route.userID }) else { return !accounts.isEmpty }
        select(account)
        switch route.kind {
        case .request:
            account.destination = .chats
            account.showingRequests = true
        case .message:
            let row = account.roomRows.first { $0.id == route.roomID }
            let destination: MacDestination = (row?.isDm ?? true) || account.isCompany ? .chats : .groups
            account.showingRequests = false
            account.destination = destination
            account.selectedRooms[destination] = route.roomID
        }
        return true
    }

    func addAccount() {
        guard linking == nil else { return }
        let account = makeAccount()
        linking = account
        account.start()
    }

    func cancelLink() {
        guard !accounts.isEmpty, linking?.phase != .keys, linking?.phase != .approving else { return }
        linking?.cancelLink()
        linking = nil
    }
}

enum MacDestination: String, CaseIterable, Identifiable {
    case chats, groups, work, you
    var id: Self { self }
    var title: String {
        switch self {
        case .chats: BipeStrings.tabChats
        case .groups: BipeStrings.tabGroups
        case .work: BipeStrings.tabWork
        case .you: BipeStrings.tabYou
        }
    }
    var systemIcon: String {
        switch self {
        case .chats: "bubble.left.and.bubble.right"
        case .groups: "person.3"
        case .work: "briefcase"
        case .you: "person.crop.circle"
        }
    }
    static func visible(isCompany: Bool) -> [Self] { [.chats, isCompany ? .work : .groups, .you] }

    func includes(isDm: Bool, isCompany: Bool) -> Bool {
        self == .chats ? (isCompany || isDm) : self == .groups && !isDm
    }
}
