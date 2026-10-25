// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UIKit

/// Type NotificationMode of spec notification-settings.
enum NotificationMode: Hashable {
    case all
    case mentions
    case off

    init(_ mode: RoomNotificationMode) {
        switch mode {
        case .allMessages: self = .all
        case .mentionsAndKeywordsOnly: self = .mentions
        case .mute: self = .off
        }
    }

    var sdkMode: RoomNotificationMode {
        switch self {
        case .all: .allMessages
        case .mentions: .mentionsAndKeywordsOnly
        case .off: .mute
        }
    }

    var label: String {
        switch self {
        case .all: BipeStrings.appNotificationModeAll
        case .mentions: BipeStrings.appNotificationModeMentions
        case .off: BipeStrings.appNotificationModeOff
        }
    }
}

/// Calls back when the push rules change, also on another device.
final class NotificationSettingsListener: NotificationSettingsDelegate {
    private let onChange: @Sendable () -> Void

    init(onChange: @escaping @Sendable () -> Void) {
        self.onChange = onChange
    }

    func settingsDidChange() {
        onChange()
    }
}

/// Spec notification-settings (bipe-shared product/screens/notification-settings.json).
@MainActor
final class NotificationSettingsModel: ObservableObject {
    @Published private(set) var phoneEnabled = false
    @Published private(set) var chatsMode: NotificationMode = .all
    @Published private(set) var groupsMode: NotificationMode = .mentions
    @Published private(set) var wholeGroupMentions = true
    @Published private(set) var invites = true
    @Published private(set) var sound = NotificationSound.phone
    /// Spec notification-settings, state preview (per account on this phone).
    @Published private(set) var preview = true
    /// Spec notification-settings, state isCompany: the opening-hours row shows.
    @Published private(set) var isCompany = false
    /// The opening hours, for the row's value. `nil` until read or for a personal account.
    @Published var workHours: BipeWorkHours?
    @Published private(set) var mentionsMayMiss = false
    @Published private(set) var hasOlderChoices = false
    @Published private(set) var isLoaded = false
    /// The older-choices dialog, with the change that waits for it.
    @Published var pendingGroupsMode: NotificationMode?
    @Published var showsError = false
    @Published var showsToneError = false
    /// Spec news: the switch for Novidades do Bipe (ADR 0117). unknown hides it.
    @Published private(set) var news: NewsSwitch = .unknown
    @Published private(set) var newsBusy = false
    @Published var showsNewsError = false

    enum NewsSwitch: Equatable {
        case unknown
        case on(roomId: String)
        case off
    }

    private let session: AccountSession
    private let api: BipeAPIClient
    private var settings: NotificationSettings?
    private var listener: NotificationSettingsListener?

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    /// Action appear: reads the permission and the push rules, and follows changes from other devices.
    func appear() async {
        await refreshPhone()
        if settings == nil {
            let settings = await session.client.getNotificationSettings()
            let listener = NotificationSettingsListener { [weak self] in
                Task { @MainActor in await self?.load() }
            }
            settings.setDelegate(delegate: listener)
            self.settings = settings
            self.listener = listener
        }
        await load()
        await loadNews()
        await loadWorkSide()
    }

    /// news: on only when the API has the user subscribed and the user is joined to or invited in the room.
    func loadNews() async {
        let api = api
        guard let state = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, { (token: String) async throws(BipeAPIError) -> BipeNewsState in
            try await api.newsState(accessToken: token)
        }) else {
            news = .unknown
            return
        }
        news = isInRoom(state) ? .on(roomId: state.roomId ?? "") : .off
    }

    /// Action setNews. on: PUT, then join the room. off: leave the room, then DELETE. A failure puts the switch back.
    func setNews(_ isOn: Bool) async {
        guard !newsBusy, news != .unknown else { return }
        let old = news
        newsBusy = true
        defer { newsBusy = false }
        let api = api
        do {
            if isOn {
                let state = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeNewsState in
                    try await api.setNews(true, accessToken: token)
                }
                guard let roomId = state.roomId else { throw BipeAPIError.unexpectedResponse(statusCode: 200) }
                if try session.client.getRoom(roomId: roomId)?.membership() != .joined {
                    _ = try await session.client.joinRoomById(roomId: roomId)
                }
                news = .on(roomId: roomId)
            } else {
                if case .on(let roomId) = old, let room = try? session.client.getRoom(roomId: roomId) {
                    try await room.leave()
                }
                _ = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeNewsState in
                    try await api.setNews(false, accessToken: token)
                }
                news = .off
            }
        } catch {
            news = old
            showsNewsError = true
        }
    }

    private func isInRoom(_ state: BipeNewsState) -> Bool {
        guard state.subscribed, let roomId = state.roomId,
              let membership = try? session.client.getRoom(roomId: roomId)?.membership() else { return false }
        return membership == .joined || membership == .invited
    }

    func refreshPhone() async {
        let push = PushRegistration.shared
        await push.appBecameActive()
        phoneEnabled = push.permission == .allowed && push.registration != nil
    }

    /// Action enablePhone: asks the permission once, else opens the app's system settings.
    func enablePhone() async {
        let push = PushRegistration.shared
        await push.appBecameActive()
        if push.permission == .notAsked {
            await push.requestPermission()
            await refreshPhone()
        } else if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
            await UIApplication.shared.open(url)
        }
    }

    /// Action setChatsMode: all or off, for one-to-one rooms, encrypted and not.
    func setChatsMode(_ mode: NotificationMode) async {
        let old = chatsMode
        chatsMode = mode
        await write(restore: { $0.chatsMode = old }) { settings in
            try await Self.setDefault(settings, isOneToOne: true, mode: mode)
        }
    }

    /// Action setGroupsMode: with older choices on the account, the dialog comes first.
    func setGroupsMode(_ mode: NotificationMode) async {
        if hasOlderChoices {
            pendingGroupsMode = mode
            return
        }
        await applyGroupsMode(mode)
    }

    /// The dialog's continue button.
    func confirmGroupsMode() async {
        guard let mode = pendingGroupsMode else { return }
        pendingGroupsMode = nil
        hasOlderChoices = false
        await applyGroupsMode(mode)
    }

    func setWholeGroupMentions(_ isOn: Bool) async {
        let old = wholeGroupMentions
        wholeGroupMentions = isOn
        await write(restore: { $0.wholeGroupMentions = old }) { try await $0.setRoomMentionEnabled(enabled: isOn) }
    }

    func setInvites(_ isOn: Bool) async {
        let old = invites
        invites = isOn
        await write(restore: { $0.invites = old }) { try await $0.setInviteForMeEnabled(enabled: isOn) }
    }

    /// Action setSound: a setting of this account on this phone; the notification service extension reads it.
    func setSound(_ sound: NotificationSound) {
        NotificationSound.set(sound, for: session.userId)
        self.sound = NotificationSound.current(for: session.userId)
        if self.sound != sound {
            showsToneError = true
        }
    }

    /// Action setPreview.
    func setPreview(_ isOn: Bool) {
        NotificationPrefs.setPreview(isOn, for: session.userId)
        preview = NotificationPrefs.preview(for: session.userId)
    }

    var accountUserId: String { session.userId }

    /// Reads whether the account is a company and, if so, its opening hours (spec work-hours).
    func loadWorkSide() async {
        let api = api
        guard let status = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, { (token: String) async throws(BipeAPIError) -> BipeAccountStatus in
            try await api.account(accessToken: token)
        }) else { return }
        isCompany = status.accountType == .company
        if isCompany {
            workHours = await WorkHoursSync.refresh(session: session, api: api)
        }
    }

    private func applyGroupsMode(_ mode: NotificationMode) async {
        let old = groupsMode
        groupsMode = mode
        await write(restore: { $0.groupsMode = old }) { settings in
            try await Self.setDefault(settings, isOneToOne: false, mode: mode)
        }
    }

    private func load() async {
        guard let settings else { return }
        // The screen reads the encrypted value and writes both (spec notes).
        let chats = await settings.getDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: true)
        let groups = await settings.getDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: false)
        let chatsPlain = await settings.getDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: true)
        let groupsPlain = await settings.getDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: false)
        chatsMode = NotificationMode(chats)
        groupsMode = NotificationMode(groups)
        wholeGroupMentions = (try? await settings.isRoomMentionEnabled()) ?? wholeGroupMentions
        invites = (try? await settings.isInviteForMeEnabled()) ?? invites
        mentionsMayMiss = !(await settings.canPushEncryptedEventToDevice())
        let hasKeywords = await settings.containsKeywordsRules()
        hasOlderChoices = chats != chatsPlain || groups != groupsPlain || hasKeywords
        sound = NotificationSound.current(for: session.userId)
        preview = NotificationPrefs.preview(for: session.userId)
        isLoaded = true
    }

    /// A failed rule change puts the row back and shows bipe_error_generic.
    private func write(restore: (NotificationSettingsModel) -> Void,
                       _ change: (NotificationSettings) async throws -> Void) async {
        guard let settings else { return }
        do {
            try await change(settings)
        } catch {
            restore(self)
            showsError = true
        }
    }

    private static func setDefault(_ settings: NotificationSettings, isOneToOne: Bool, mode: NotificationMode) async throws {
        try await settings.setDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: isOneToOne, mode: mode.sdkMode)
        try await settings.setDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: isOneToOne, mode: mode.sdkMode)
    }
}
