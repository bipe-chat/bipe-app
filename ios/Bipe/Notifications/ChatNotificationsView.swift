// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec chat-notifications (bipe-shared product/screens/chat-notifications.json).
@MainActor
final class ChatNotificationsModel: ObservableObject {
    @Published private(set) var isDm = true
    @Published private(set) var mode: NotificationMode = .all
    @Published private(set) var isDefault = true
    @Published private(set) var defaultMode: NotificationMode = .all
    @Published private(set) var mentionsMayMiss = false
    @Published private(set) var isLoaded = false
    @Published var showsError = false

    let roomId: String
    private let session: AccountSession
    private var settings: NotificationSettings?
    private var isEncrypted = true
    private var isOneToOne = true

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    /// DM: all and off only (ADR 0141). A DM with an older mentions rule keeps it on the list until changed.
    var options: [NotificationMode] {
        isDm && mode != .mentions ? [.all, .off] : [.all, .mentions, .off]
    }

    /// Action appear.
    func appear() async {
        guard let room = try? session.client.getRoom(roomId: roomId), let info = try? await room.roomInfo() else { return }
        isDm = info.isDm
        // Push rules count members: a one-to-one room has two.
        isOneToOne = info.activeMembersCount == 2
        if case .notEncrypted = (try? await room.latestEncryptionState()) ?? .encrypted {
            isEncrypted = false
        }
        let settings = await session.client.getNotificationSettings()
        self.settings = settings
        await load(settings)
    }

    /// Action useDefault: removes the chat's own rule, so it follows Settings.
    func useDefault() async {
        await write { try await $0.restoreDefaultRoomNotificationMode(roomId: self.roomId) }
    }

    /// Action setMode. Leaving off uses unmuteRoom, so the room goes back to its previous mode.
    func setMode(_ newMode: NotificationMode) async {
        let wasOff = mode == .off && !isDefault
        await write { settings in
            if wasOff && newMode != .off {
                try await settings.unmuteRoom(roomId: self.roomId, isEncrypted: self.isEncrypted, isOneToOne: self.isOneToOne)
            }
            try await settings.setRoomNotificationMode(roomId: self.roomId, mode: newMode.sdkMode)
        }
    }

    private func load(_ settings: NotificationSettings) async {
        if let room = try? await settings.getRoomNotificationSettings(roomId: roomId, isEncrypted: isEncrypted, isOneToOne: isOneToOne) {
            mode = NotificationMode(room.mode)
            isDefault = room.isDefault
        }
        defaultMode = NotificationMode(await settings.getDefaultRoomNotificationMode(isEncrypted: isEncrypted, isOneToOne: isOneToOne))
        let canPushEncrypted = await settings.canPushEncryptedEventToDevice()
        mentionsMayMiss = isEncrypted && !canPushEncrypted
        isLoaded = true
    }

    /// A failure puts the old value back and shows bipe_error_generic.
    private func write(_ change: @escaping (NotificationSettings) async throws -> Void) async {
        guard let settings else { return }
        do {
            try await change(settings)
        } catch {
            showsError = true
        }
        await load(settings)
    }
}

/// Spec chat-notifications: every message, only mentions (groups) or silenced, or the Settings default.
struct ChatNotificationsView: View {
    @StateObject private var model: ChatNotificationsModel

    init(session: AccountSession, roomId: String) {
        _model = StateObject(wrappedValue: ChatNotificationsModel(session: session, roomId: roomId))
    }

    var body: some View {
        List {
            Section {
                option(title: BipeStrings.appNotificationModeDefault(model.defaultMode.label), isSelected: model.isDefault) {
                    Task { await model.useDefault() }
                }
                ForEach(model.options, id: \.self) { mode in
                    option(title: mode.label, isSelected: !model.isDefault && model.mode == mode) {
                        Task { await model.setMode(mode) }
                    }
                }
            } footer: {
                if model.isDm && model.mode == .mentions && !model.isDefault {
                    Text(BipeStrings.dmNotificationsMentionsDisclaimer)
                } else if !model.isDm && model.mode == .mentions && model.mentionsMayMiss {
                    Text(BipeStrings.groupScreenRoomNotificationSettingsMentionsOnlyDisclaimer)
                }
            }
            .disabled(!model.isLoaded)
        }
        .tint(BipeColor.accentFill)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(model.isDm ? BipeStrings.dmNotificationsCustomTitle : BipeStrings.groupScreenRoomNotificationSettingsRoomCustomSettingsTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.chatNotifications.rawValue)
        .task { await model.appear() }
        .alert(BipeStrings.errorGeneric, isPresented: $model.showsError) {}
    }

    private func option(title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .foregroundStyle(BipeColor.inkPrimary)
                Spacer()
                if isSelected {
                    PhosphorIcon.check.image
                        .resizable()
                        .frame(width: 18, height: 18)
                        .foregroundStyle(BipeColor.accentInk)
                        .accessibilityHidden(true)
                }
            }
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
