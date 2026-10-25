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

    @Published private(set) var whoCanMessage: BipeWhoCanMessage?
    @Published private(set) var account: BipeAccountStatus?
    /// Spec phone-link-status: `nil` until GET /v1/me/phone answers.
    @Published private(set) var phone: BipePhoneLinkState?
    @Published private(set) var hasRecoveryKey = false
    @Published private(set) var notificationsOn: Bool?
    @Published private(set) var appLockOn = AppLockModel.shared.store.isOn
    @Published private(set) var biometricUnlockOn = AppLockModel.shared.store.biometricUnlock

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    /// `nil` until GET /v1/me/account answers.
    var waysBack: BipeWaysBack? {
        guard let account else { return nil }
        let token = account.recoveryTokenSet && BipeCredentialStore().recoveryToken(for: session.userId) != nil
        return BipeWaysBack(hasEmail: account.email != nil,
                            // Linked Apple or Google accounts come with bipe-chat/bipe#307.
                            hasLinkedAccount: false,
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
        _ = await (messaging, account, notifications, phone)
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
        }
    }

    private func loadAccount() async {
        let api = api
        if let value = try? await authorized({ (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) {
            account = value
        }
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
        case .profile, .chats, .help: nil
        }
    }

    func needsAttention(_ group: BipeSettingsGroup) -> Bool {
        group == .account && waysBack?.needsWarning == true
    }
}
