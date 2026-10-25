// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec notification-settings: the Notifications group of Settings. Every row says its current value.
struct NotificationSettingsView: View {
    @StateObject private var model: NotificationSettingsModel
    @Environment(\.scenePhase) private var scenePhase
    private let session: AccountSession
    private let api: BipeAPIClient

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
        _model = StateObject(wrappedValue: NotificationSettingsModel(session: session, api: api))
    }

    var body: some View {
        List {
            if !model.phoneEnabled {
                phoneCard
            }
            Section {
                modePicker(BipeStrings.notificationsChats,
                           selection: model.chatsMode,
                           // ADR 0141: no mentions mode in a DM. An older mentions rule still shows until changed.
                           options: model.chatsMode == .mentions ? [.all, .mentions, .off] : [.all, .off]) { mode in
                    Task { await model.setChatsMode(mode) }
                }
                VStack(alignment: .leading, spacing: 6) {
                    modePicker(BipeStrings.notificationsGroups, selection: model.groupsMode, options: [.all, .mentions, .off]) { mode in
                        Task { await model.setGroupsMode(mode) }
                    }
                    if model.groupsMode == .mentions && model.mentionsMayMiss {
                        Text(BipeStrings.groupScreenNotificationSettingsMentionsOnlyDisclaimer)
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                Toggle(isOn: binding(model.wholeGroupMentions) { isOn in Task { await model.setWholeGroupMentions(isOn) } }) {
                    Text(BipeStrings.notificationsWholeGroup)
                }
                Toggle(isOn: binding(model.invites) { isOn in Task { await model.setInvites(isOn) } }) {
                    Text(BipeStrings.notificationsInvites)
                }
            } header: {
                Text(BipeStrings.notificationsNotifyAbout)
            }
            .disabled(!model.isLoaded)

            Section {
                Picker(selection: binding(model.sound) { model.setSound($0) }) {
                    ForEach(NotificationSound.allCases, id: \.self) { sound in
                        Text(sound.label).tag(sound)
                    }
                } label: {
                    Text(BipeStrings.notificationsMessageSound)
                }
                .accessibilityValue(BipeStrings.notificationsToneCurrent(model.sound.label))
                // bipe-chat/bipe#511: per account on this phone.
                Toggle(isOn: binding(model.preview) { model.setPreview($0) }) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(BipeStrings.notificationsPreview)
                        Text(BipeStrings.notificationsPreviewFooter)
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            } header: {
                Text(BipeStrings.notificationsSound)
            } footer: {
                Text(BipeStrings.notificationsAccountFooter(BipeAccountLabels.label(for: model.accountUserId)))
            }

            // Spec work-hours (ADR 0138 rule 4): company accounts only.
            if model.isCompany {
                Section {
                    NavigationLink {
                        WorkHoursView(session: session, api: api) { model.workHours = $0 }
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(BipeStrings.workHoursTitle)
                            if let hours = model.workHours, hours.enabled {
                                Text(hours.isOpen(at: .now) ? BipeStrings.workHoursOpenNow : BipeStrings.workHoursClosedNow)
                                    .font(.footnote)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                        }
                    }
                } footer: {
                    Text(BipeStrings.workHoursBody)
                }
            }

            if model.news != .unknown {
                Section {
                    Toggle(isOn: binding(model.news != .off) { isOn in Task { await model.setNews(isOn) } }) {
                        Text(BipeStrings.settingsNews)
                    }
                    .disabled(model.newsBusy)
                } footer: {
                    Text(BipeStrings.settingsNewsBody)
                }
            }
        }
        .tint(BipeColor.accentFill)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.settingsNotifications)
        .accessibilityIdentifier(BipeScreen.notificationSettings.rawValue)
        .task { await model.appear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.refreshPhone() }
            }
        }
        .alert(BipeStrings.notificationsMismatchTitle, isPresented: Binding(
            get: { model.pendingGroupsMode != nil },
            set: { if !$0 { model.pendingGroupsMode = nil } }
        )) {
            Button(role: .cancel) {
                model.pendingGroupsMode = nil
            } label: {
                Text(BipeStrings.appCancel)
            }
            Button {
                Task { await model.confirmGroupsMode() }
            } label: {
                Text(BipeStrings.actionContinue)
            }
        } message: {
            Text(BipeStrings.notificationsMismatchBody)
        }
        .alert(BipeStrings.errorGeneric, isPresented: $model.showsError) {}
        .alert(BipeStrings.notificationsToneError, isPresented: $model.showsToneError) {}
        .alert(BipeStrings.settingsNewsFailed, isPresented: $model.showsNewsError) {}
    }

    /// phoneEnabled false: the card at the top. The rows below stay editable: they apply on every device.
    private var phoneCard: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text(BipeStrings.settingsNotificationsOff)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.notificationsPermissionBody)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button {
                    Task { await model.enablePhone() }
                } label: {
                    Text(BipeStrings.notificationsEnable)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.vertical, 4)
        }
    }

    private func modePicker(_ title: String,
                            selection: NotificationMode,
                            options: [NotificationMode],
                            set: @escaping (NotificationMode) -> Void) -> some View {
        Picker(selection: binding(selection, set: set)) {
            ForEach(options, id: \.self) { mode in
                Text(mode.label).tag(mode)
            }
        } label: {
            Text(title)
        }
    }

    private func binding<Value>(_ value: Value, set: @escaping (Value) -> Void) -> Binding<Value> {
        Binding(get: { value }, set: set)
    }
}

extension NotificationSound {
    var label: String {
        switch self {
        case .phone: BipeStrings.notificationsToneSystem
        case .soft: BipeStrings.notificationsToneSoft
        case .silent: BipeStrings.notificationsToneSilent
        }
    }
}
