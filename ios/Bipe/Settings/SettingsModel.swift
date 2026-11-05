// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UserNotifications

/// The state that settings-root and settings-group read: who can message, the ways back, the notification permission.
/// One model for the whole settings stack, so a group shows what the root already read.
@MainActor
final class SettingsModel: ObservableObject {
    let session: AccountSession
    let api: BipeAPIClient

    @Published private(set) var whoCanMessage: BipeWhoCanMessage? { didSet { remember() } }
    @Published private(set) var account: BipeAccountStatus? { didSet { remember() } }
    /// Spec phone-link-status: `nil` until GET /v1/me/phone answers.
    @Published private(set) var phone: BipePhoneLinkState? { didSet { remember() } }
    /// The own linked number in E.164 from GET /v1/me/phone (ADR 0191), `nil` for a link made before the server stored it.
    @Published private(set) var phoneNumber: String? { didSet { remember() } }
    /// Spec phone-link-status, move (bipe-chat/bipe#469). Not kept between visits: it always comes fresh from the server.
    @Published private(set) var phoneMove: BipePhoneMove?
    /// The discovery switch (PUT /v1/me/discovery): `nil` until GET /v1/me/phone answers.
    @Published private(set) var discoverableByPhone: Bool? { didSet { remember() } }
    @Published private(set) var hasRecoveryKey = false { didSet { remember() } }
    @Published private(set) var notificationsOn: Bool? { didSet { remember() } }
    @Published private(set) var appLockOn = AppLockModel.shared.store.isOn
    @Published private(set) var biometricUnlockOn = AppLockModel.shared.store.biometricUnlock
    /// The assistant send switch (ADR 0210): global account data chat.bipe.assistant_send. `nil` until it is read.
    @Published private(set) var assistantSend: Bool? { didSet { remember() } }

    /// The last values read, per account, for this app run. A new model starts from them, so settings opens with its
    /// subtitles and switches filled and `load()` only refreshes them. Memory only: nothing goes to disk.
    private struct Snapshot {
        var whoCanMessage: BipeWhoCanMessage?
        var account: BipeAccountStatus?
        var phone: BipePhoneLinkState?
        var phoneNumber: String?
        var discoverableByPhone: Bool?
        var hasRecoveryKey = false
        var notificationsOn: Bool?
        var assistantSend: Bool?
    }
    private static var snapshots: [String: Snapshot] = [:]
    private var seeding = false

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        guard let last = Self.snapshots[session.userId] else { return }
        seeding = true
        whoCanMessage = last.whoCanMessage
        account = last.account
        phone = last.phone
        phoneNumber = last.phoneNumber
        discoverableByPhone = last.discoverableByPhone
        hasRecoveryKey = last.hasRecoveryKey
        notificationsOn = last.notificationsOn
        assistantSend = last.assistantSend
        seeding = false
    }

    /// Reads the summaries ahead of the first settings open, so the first open after launch is filled too.
    static func prefetch(session: AccountSession, api: BipeAPIClient) async {
        await SettingsModel(session: session, api: api).load()
    }

    private func remember() {
        guard !seeding else { return }
        Self.snapshots[session.userId] = Snapshot(
            whoCanMessage: whoCanMessage, account: account, phone: phone, phoneNumber: phoneNumber,
            discoverableByPhone: discoverableByPhone, hasRecoveryKey: hasRecoveryKey,
            notificationsOn: notificationsOn, assistantSend: assistantSend)
    }

    /// `nil` until GET /v1/me/account answers.
    var waysBack: BipeWaysBack? {
        guard let account else { return nil }
        let token = account.recoveryTokenSet && BipeCredentialStore().recoveryToken(for: session.userId) != nil
        return BipeWaysBack(hasEmail: account.email != nil,
                            hasLinkedAccount: !(account.links ?? []).isEmpty,
                            hasRecoveryKey: hasRecoveryKey,
                            hasSyncedToken: token)
    }

    /// Spec settings-root, action appear: reads the summaries. Each fills in when it arrives.
    func load() async {
        hasRecoveryKey = session.client.encryption().recoveryState() == .enabled
        async let messaging: Void = loadMessaging()
        async let account: Void = loadAccount()
        async let notifications: Void = loadNotifications()
        async let phone: Void = loadPhone()
        async let assistant: Void = loadAssistantSend()
        _ = await (messaging, account, notifications, phone, assistant)
    }

    /// assistantSendAllowed: off when the account data is absent. A read error keeps the switch off and disabled.
    private func loadAssistantSend() async {
        do {
            let json = try await session.client.accountData(eventType: BipeAssistantSend.accountDataType)
            assistantSend = BipeAssistantSend.allowed(json: json)
        } catch {
            assistantSend = nil
        }
    }

    /// The assistant send switch: Client.setAccountData chat.bipe.assistant_send. `false` when the save failed; the
    /// value stays as it was.
    func setAssistantSend(_ value: Bool) async -> Bool {
        let old = assistantSend
        assistantSend = value
        do {
            try await session.client.setAccountData(eventType: BipeAssistantSend.accountDataType,
                                                    content: BipeAssistantSend.json(enabled: value))
            return true
        } catch {
            assistantSend = old
            return false
        }
    }

    private func loadMessaging() async {
        let api = api
        if let value = try? await authorized({ (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
            try await api.whoCanMessage(accessToken: token)
        }) {
            whoCanMessage = value
        }
    }

    private func loadPhone() async {
        let api = api
        if let value = try? await authorized({ (token: String) async throws(BipeAPIError) -> BipeMyPhone in
            try await api.myPhone(accessToken: token)
        }) {
            phone = BipePhoneLinkState(phone: value)
            phoneNumber = value.linked ? value.number : nil
            phoneMove = value.move
            discoverableByPhone = value.discoverableByPhone
        }
    }

    /// Spec phone-link-status, action blockMove. `false` when the block failed.
    func blockPhoneMove(_ move: BipePhoneMove) async -> Bool {
        let api = api
        do {
            try await authorized { (token: String) async throws(BipeAPIError) in
                try await api.blockPhoneMove(id: move.id, accessToken: token)
            }
        } catch {
            return false
        }
        await loadPhone()
        return true
    }

    /// The discovery switch: PUT /v1/me/discovery. `false` when the save failed; the value stays as it was.
    func setDiscoverableByPhone(_ value: Bool) async -> Bool {
        let api = api
        let old = discoverableByPhone
        discoverableByPhone = value
        do {
            try await authorized { (token: String) async throws(BipeAPIError) in
                try await api.setDiscoverableByPhone(value, accessToken: token)
            }
            return true
        } catch {
            discoverableByPhone = old
            return false
        }
    }

    private func loadAccount() async {
        let api = api
        if let value = try? await authorized({ (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) {
            account = value
            DeletionDate.remember(value.deletion?.dueAt, for: session.userId)
        }
    }

    /// Spec account-delete, action cancelPending: POST /v1/me/deletions/{id}/cancel. A 404 means it no longer waits,
    /// which is what the person wanted. `false` when the cancel failed.
    func cancelDeletion(_ deletion: BipeAccountDeletion) async -> Bool {
        let api = api
        do {
            try await authorized { (token: String) async throws(BipeAPIError) in
                try await api.cancelDeletion(id: deletion.id, accessToken: token)
            }
        } catch {
            guard case .server(.notFound, _, _) = error else { return false }
        }
        DeletionDate.remember(nil, for: session.userId)
        await loadAccount()
        return true
    }

    /// The who-can-message picker: PUT /v1/me/messaging. `false` when the save failed; the value stays as it was.
    func setWhoCanMessage(_ value: BipeWhoCanMessage) async -> Bool {
        let api = api
        guard let saved = try? await authorized({ (token: String) async throws(BipeAPIError) -> BipeWhoCanMessage in
            try await api.setWhoCanMessage(value, accessToken: token)
        }) else { return false }
        whoCanMessage = saved
        return true
    }

    /// Runs a bipe-api request with the account's access token, retrying once after a token refresh.
    func authorized<T>(_ request: (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, request)
    }

    private func loadNotifications() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationsOn = settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
            || settings.authorizationStatus == .ephemeral
    }

    /// Reads the app lock store again after a change.
    func refreshLock() {
        appLockOn = AppLockModel.shared.store.isOn
        biometricUnlockOn = AppLockModel.shared.store.biometricUnlock
    }

    /// The subtitle of a root row. `nil` when the group has nothing to sum up or the value has not arrived.
    func summary(of group: BipeSettingsGroup) -> String? {
        switch group {
        case .privacy: whoCanMessage?.settingsSummary
        case .account: waysBack?.summary
        case .notifications: notificationsOn.map { $0 ? BipeStrings.settingsNotificationsOn : BipeStrings.settingsNotificationsOff }
        case .help: BipeStrings.helpVersion(AppVersion.nameAndBuild)
        // chats: SettingsRootSection reads the theme and palette itself.
        case .profile, .chats: nil
        }
    }

    func needsAttention(_ group: BipeSettingsGroup) -> Bool {
        group == .account && waysBack?.needsWarning == true
    }
}
