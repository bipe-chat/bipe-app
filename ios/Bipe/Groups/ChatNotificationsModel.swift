// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Enum NotifyOption.mode of spec chat-notifications.
enum NotifyMode: Equatable {
    case quiet
    case all
    case mute
    case mentionsLegacy

    /// Mentions and keywords is quiet in a group and the old rule in a DM (ADR 0141).
    init(_ mode: RoomNotificationMode, isDm: Bool) {
        switch mode {
        case .allMessages: self = .all
        case .mute: self = .mute
        case .mentionsAndKeywordsOnly: self = isDm ? .mentionsLegacy : .quiet
        }
    }

    var sdkMode: RoomNotificationMode {
        switch self {
        case .all: .allMessages
        case .mute: .mute
        case .quiet, .mentionsLegacy: .mentionsAndKeywordsOnly
        }
    }

    var title: String {
        switch self {
        case .quiet: BipeStrings.appNotifyQuiet
        case .all: BipeStrings.appNotifyAll
        case .mute: BipeStrings.appNotifyMute
        case .mentionsLegacy: BipeStrings.appNotifyMentionsLegacy
        }
    }
}

/// Type NotifyOption of spec chat-notifications.
struct NotifyOption: Identifiable, Equatable {
    var mode: NotifyMode
    var footer: String?
    var isDefault: Bool

    var id: String { mode.title }
}

/// Spec chat-notifications (bipe-shared product/screens/chat-notifications.json).
@MainActor
final class ChatNotificationsModel: ObservableObject {
    @Published private(set) var isDm = false
    @Published private(set) var options: [NotifyOption] = []
    @Published private(set) var current: NotifyMode?
    @Published private(set) var saving = false
    @Published var showsError = false

    let roomId: String
    private let session: AccountSession
    private var isEncrypted = true
    private var defaultMode: NotifyMode?
    private var canPushEncrypted = true

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    /// The current mode of a chat, for the row in group-details.
    static func currentMode(client: Client, roomId: String) async -> NotifyMode? {
        guard let room = try? client.getRoom(roomId: roomId), let info = try? await room.roomInfo() else { return nil }
        let settings = await client.getNotificationSettings()
        guard let roomSettings = try? await settings.getRoomNotificationSettings(roomId: roomId,
                                                                                 isEncrypted: info.encryptionState != .notEncrypted,
                                                                                 isOneToOne: info.isDm) else { return nil }
        return NotifyMode(roomSettings.mode, isDm: info.isDm)
    }

    /// Action appear.
    func appear() async {
        guard let room = try? session.client.getRoom(roomId: roomId), let info = try? await room.roomInfo() else { return }
        isDm = info.isDm
        isEncrypted = info.encryptionState != .notEncrypted
        let settings = await session.client.getNotificationSettings()
        defaultMode = NotifyMode(await settings.getDefaultRoomNotificationMode(isEncrypted: isEncrypted, isOneToOne: isDm), isDm: isDm)
        canPushEncrypted = await settings.canHomeserverPushEncryptedEventToDevice()
        if let roomSettings = try? await settings.getRoomNotificationSettings(roomId: roomId, isEncrypted: isEncrypted, isOneToOne: isDm) {
            current = NotifyMode(roomSettings.mode, isDm: isDm)
        }
        rebuildOptions()
    }

    /// Action choose.
    func choose(_ mode: NotifyMode) async {
        guard !saving, mode != .mentionsLegacy, let old = current, mode != old else { return }
        saving = true
        current = mode
        defer { saving = false }
        do {
            let settings = await session.client.getNotificationSettings()
            if old == .mute {
                try await settings.unmuteRoom(roomId: roomId, isEncrypted: isEncrypted, isOneToOne: isDm)
            }
            if mode == defaultMode {
                try await settings.restoreDefaultRoomNotificationMode(roomId: roomId)
            } else {
                try await settings.setRoomNotificationMode(roomId: roomId, mode: mode.sdkMode)
            }
            rebuildOptions()
        } catch {
            current = old
            showsError = true
        }
    }

    private func rebuildOptions() {
        var modes: [NotifyMode]
        var quietFooter: String? = BipeStrings.appNotifyQuietFooter
        if isDm {
            modes = [.all, .mute]
            if current == .mentionsLegacy {
                modes.append(.mentionsLegacy)
            }
        } else if isEncrypted && !canPushEncrypted {
            // Quiet would silence the group until the server pushes encrypted events (#118).
            modes = [.all, .mute]
            if current == .quiet {
                modes.insert(.quiet, at: 0)
                quietFooter = nil
            }
        } else {
            modes = [.quiet, .all, .mute]
        }
        options = modes.map { mode in
            NotifyOption(mode: mode, footer: mode == .quiet ? quietFooter : nil, isDefault: mode == defaultMode)
        }
    }
}
