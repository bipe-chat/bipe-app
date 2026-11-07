// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI
import UserNotifications

/// Spec notification-settings on the Mac: the account's rules, the Mac's system permission, the join prefs and news.
/// Sound, preview and work hours stay out until the Mac's notifications read them (bipe-chat/bipe#1167).
@MainActor
final class MacNotificationSettingsModel: ObservableObject {
    @Published private(set) var macEnabled = true
    @Published private(set) var chatsMode: NotificationMode = .all
    @Published private(set) var groupsMode: NotificationMode = .mentions
    @Published private(set) var wholeGroupMentions = true
    @Published private(set) var invites = true
    @Published private(set) var hasOlderChoices = false
    @Published private(set) var isLoaded = false
    /// The older-choices dialog, with the change that waits for it.
    @Published var pendingGroupsMode: NotificationMode?
    @Published var showsError = false
    @Published private(set) var news: NewsSwitch = .unknown
    @Published private(set) var newsBusy = false
    @Published var showsNewsError = false
    /// nil: not read, the switches hide.
    @Published private(set) var prefs: BipeNotificationPrefs?

    enum NewsSwitch: Equatable {
        case unknown
        case on(roomId: String)
        case off
    }

    let account: MacAccount
    private var settings: NotificationSettings?
    private var listener: NotificationSettingsListener?

    init(account: MacAccount) { self.account = account }

    /// Action appear: reads the permission and the push rules, and follows changes from other devices.
    func appear() async {
        await refreshPermission()
        guard let client = account.client else { return }
        if settings == nil {
            let settings = await client.getNotificationSettings()
            let listener = NotificationSettingsListener { [weak self] in
                Task { @MainActor in await self?.load() }
            }
            settings.setDelegate(delegate: listener)
            self.settings = settings
            self.listener = listener
        }
        await load()
        await loadNews()
        await loadPrefs()
    }

    func refreshPermission() async {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        macEnabled = status == .authorized || status == .provisional
    }

    /// Action enablePhone on the Mac: asks once, then opens System Settings.
    func enable() async {
        let center = UNUserNotificationCenter.current()
        if await center.notificationSettings().authorizationStatus == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
            await refreshPermission()
        } else if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }

    func setChatsMode(_ mode: NotificationMode) async {
        let old = chatsMode
        chatsMode = mode
        await write(restore: { $0.chatsMode = old }) { settings in
            try await NotificationRules.setDefault(settings, isOneToOne: true, mode: mode)
        }
    }

    /// With older choices on the account, the dialog comes first.
    func setGroupsMode(_ mode: NotificationMode) async {
        if hasOlderChoices {
            pendingGroupsMode = mode
            return
        }
        await applyGroupsMode(mode)
    }

    func confirmGroupsMode() async {
        guard let mode = pendingGroupsMode else { return }
        pendingGroupsMode = nil
        hasOlderChoices = false
        await applyGroupsMode(mode)
    }

    private func applyGroupsMode(_ mode: NotificationMode) async {
        let old = groupsMode
        groupsMode = mode
        await write(restore: { $0.groupsMode = old }) { settings in
            try await NotificationRules.setDefault(settings, isOneToOne: false, mode: mode)
        }
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

    private func load() async {
        guard let settings else { return }
        // Chats: reads the encrypted value and writes both. Groups: reads the plain value (spec notes, ADR 0254).
        let chats = await settings.getDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: true)
        let chatsPlain = await settings.getDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: true)
        let groups = await settings.getDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: false)
        chatsMode = NotificationMode(chats)
        groupsMode = NotificationMode(groups)
        wholeGroupMentions = (try? await settings.isRoomMentionEnabled()) ?? wholeGroupMentions
        invites = (try? await settings.isInviteForMeEnabled()) ?? invites
        let hasKeywords = await settings.containsKeywordsRules()
        hasOlderChoices = chats != chatsPlain || hasKeywords
        isLoaded = true
    }

    /// A failed rule change puts the row back and shows bipe_error_generic.
    private func write(restore: (MacNotificationSettingsModel) -> Void,
                       _ change: (NotificationSettings) async throws -> Void) async {
        guard let settings else { return }
        do {
            try await change(settings)
        } catch {
            restore(self)
            showsError = true
        }
    }

    private func loadPrefs() async {
        let api = account.api
        prefs = try? await account.authorized { (token: String) async throws(BipeAPIError) -> BipeNotificationPrefs in
            try await api.notificationPrefs(accessToken: token)
        }
    }

    func setInviteJoined(_ isOn: Bool) async { await changePrefs(inviteJoined: isOn) }
    func setGroupLinkJoined(_ isOn: Bool) async { await changePrefs(groupLinkJoined: isOn) }
    func setBookingReminders(_ isOn: Bool) async { await changePrefs(bookingReminders: isOn) }
    func setWorkAlerts(_ isOn: Bool) async { await changePrefs(workAlerts: isOn) }
    func setWorkSummary(_ isOn: Bool) async { await changePrefs(workSummary: isOn) }

    /// Shows the new value at once; a failure puts the switch back and shows bipe_error_generic.
    private func changePrefs(inviteJoined: Bool? = nil, groupLinkJoined: Bool? = nil, bookingReminders: Bool? = nil,
                             workAlerts: Bool? = nil, workSummary: Bool? = nil) async {
        guard let old = prefs else { return }
        let new = BipeNotificationPrefs(inviteJoined: inviteJoined ?? old.inviteJoined,
                                        groupLinkJoined: groupLinkJoined ?? old.groupLinkJoined,
                                        bookingReminders: bookingReminders ?? old.bookingReminders,
                                        workAlerts: workAlerts ?? old.workAlerts,
                                        workSummary: workSummary ?? old.workSummary)
        prefs = new
        let api = account.api
        do {
            prefs = try await account.authorized { (token: String) async throws(BipeAPIError) -> BipeNotificationPrefs in
                try await api.setNotificationPrefs(new, accessToken: token)
            }
        } catch {
            prefs = old
            showsError = true
        }
    }

    /// news: on only when the API has the user subscribed and the user is joined to or invited in the room.
    private func loadNews() async {
        let api = account.api
        guard let state = try? await account.authorized({ (token: String) async throws(BipeAPIError) -> BipeNewsState in
            try await api.newsState(accessToken: token)
        }) else {
            news = .unknown
            return
        }
        news = isInRoom(state) ? .on(roomId: state.roomId ?? "") : .off
    }

    private func isInRoom(_ state: BipeNewsState) -> Bool {
        guard state.subscribed, let roomId = state.roomId,
              let membership = try? account.client?.getRoom(roomId: roomId)?.membership() else { return false }
        return membership == .joined || membership == .invited
    }

    /// Action setNews. on: PUT, then join the room. off: leave the room, then DELETE. A failure puts the switch back.
    func setNews(_ isOn: Bool) async {
        guard !newsBusy, news != .unknown, let client = account.client else { return }
        let old = news
        newsBusy = true
        defer { newsBusy = false }
        let api = account.api
        do {
            if isOn {
                let state = try await account.authorized { (token: String) async throws(BipeAPIError) -> BipeNewsState in
                    try await api.setNews(true, accessToken: token)
                }
                guard let roomId = state.roomId else { throw BipeAPIError.unexpectedResponse(statusCode: 200) }
                if try client.getRoom(roomId: roomId)?.membership() != .joined {
                    _ = try await client.joinRoomById(roomId: roomId)
                }
                news = .on(roomId: roomId)
            } else {
                if case .on(let roomId) = old, let room = try? client.getRoom(roomId: roomId) {
                    try await room.leave()
                }
                _ = try await account.authorized { (token: String) async throws(BipeAPIError) -> BipeNewsState in
                    try await api.setNews(false, accessToken: token)
                }
                news = .off
            }
        } catch {
            news = old
            showsNewsError = true
        }
    }
}

/// Spec notification-settings: the Notifications group of Settings. Every row says its current value.
struct MacNotificationSettingsView: View {
    @StateObject private var model: MacNotificationSettingsModel
    @Environment(\.scenePhase) private var scenePhase

    init(account: MacAccount) {
        _model = StateObject(wrappedValue: MacNotificationSettingsModel(account: account))
    }

    var body: some View {
        Form {
            if !model.macEnabled { permissionCard }
            Section {
                modePicker(BipeStrings.notificationsChats, isDm: true, selection: model.chatsMode,
                           // ADR 0141: no mentions mode in a DM. An older mentions rule still shows until changed.
                           options: model.chatsMode == .mentions ? [.all, .mentions, .off] : [.all, .off]) { mode in
                    Task { await model.setChatsMode(mode) }
                }
                modePicker(BipeStrings.notificationsGroups, isDm: false, selection: model.groupsMode, options: [.all, .mentions, .off]) { mode in
                    Task { await model.setGroupsMode(mode) }
                }
                Toggle(BipeStrings.notificationsWholeGroup, isOn: binding(model.wholeGroupMentions) { isOn in Task { await model.setWholeGroupMentions(isOn) } })
                Toggle(BipeStrings.notificationsInvites, isOn: binding(model.invites) { isOn in Task { await model.setInvites(isOn) } })
            } header: {
                Text(BipeStrings.notificationsNotifyAbout)
            } footer: {
                Text(BipeStrings.notificationsAccountFooter(BipeAccountLabels.label(for: model.account.userId)))
            }
            .disabled(!model.isLoaded)

            // Join pushes (bipe-chat/bipe#967): account settings on the server. Hidden until read.
            if let prefs = model.prefs {
                Section {
                    Toggle(BipeStrings.notificationsInviteJoined, isOn: binding(prefs.inviteJoined) { isOn in Task { await model.setInviteJoined(isOn) } })
                    Toggle(BipeStrings.notificationsGroupLinkJoined, isOn: binding(prefs.groupLinkJoined) { isOn in Task { await model.setGroupLinkJoined(isOn) } })
                    Toggle(BipeStrings.notificationsBookingReminders, isOn: binding(prefs.bookingReminders) { isOn in Task { await model.setBookingReminders(isOn) } })
                    if model.account.isCompany {
                        Toggle(BipeStrings.notificationsWorkAlerts, isOn: binding(prefs.workAlerts) { isOn in Task { await model.setWorkAlerts(isOn) } })
                        Toggle(BipeStrings.notificationsWorkSummary, isOn: binding(prefs.workSummary) { isOn in Task { await model.setWorkSummary(isOn) } })
                    }
                } footer: {
                    Text(BipeStrings.notificationsJoinsFooter)
                }
            }

            if model.news != .unknown {
                Section {
                    Toggle(BipeStrings.settingsNews, isOn: binding(model.news != .off) { isOn in Task { await model.setNews(isOn) } })
                        .disabled(model.newsBusy)
                } footer: {
                    Text(BipeStrings.settingsNewsBody)
                }
            }
        }
        .formStyle(.grouped)
        .macFont(.body)
        .tint(BipeColor.accentFill)
        .navigationTitle(BipeStrings.settingsNotifications)
        .accessibilityIdentifier(BipeScreen.notificationSettings.rawValue)
        .task { await model.appear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.refreshPermission() } }
        }
        .alert(BipeStrings.notificationsMismatchTitle, isPresented: Binding(
            get: { model.pendingGroupsMode != nil },
            set: { if !$0 { model.pendingGroupsMode = nil } }
        )) {
            Button(BipeStrings.appCancel, role: .cancel) { model.pendingGroupsMode = nil }
            Button(BipeStrings.actionContinue) { Task { await model.confirmGroupsMode() } }
        } message: {
            Text(BipeStrings.notificationsMismatchBody)
        }
        .alert(BipeStrings.errorGeneric, isPresented: $model.showsError) {}
        .alert(BipeStrings.settingsNewsFailed, isPresented: $model.showsNewsError) {}
    }

    /// The system permission is off: the card at the top. The rows below stay editable: they apply on every device.
    private var permissionCard: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(BipeStrings.macNotificationsOff).macFont(.headline).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.macNotificationsPermissionBody).macFont(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.macNotificationsEnable) { Task { await model.enable() } }.buttonStyle(.bipeProminent)
            }.padding(.vertical, 4)
        }
    }

    private func modePicker(_ title: String, isDm: Bool, selection: NotificationMode, options: [NotificationMode],
                            set: @escaping (NotificationMode) -> Void) -> some View {
        Picker(title, selection: binding(selection, set: set)) {
            ForEach(options, id: \.self) { mode in
                Text(mode.label(isDm: isDm)).tag(mode)
            }
        }
    }

    private func binding<Value>(_ value: Value, set: @escaping (Value) -> Void) -> Binding<Value> {
        Binding(get: { value }, set: set)
    }
}
