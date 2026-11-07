// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

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

    /// One name per mode on every screen: in a group, mentions is the quiet mode of chat-notifications.
    func label(isDm: Bool) -> String {
        switch self {
        case .all: BipeStrings.appNotificationModeAll
        case .mentions: isDm ? BipeStrings.appNotifyMentionsLegacy : BipeStrings.appNotifyQuiet
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

/// The account's default push rules (spec notification-settings notes). The phone and Mac screens share them.
@MainActor
enum NotificationRules {
    static func setDefault(_ settings: NotificationSettings, isOneToOne: Bool, mode: NotificationMode) async throws {
        // Mentions in a group: the encrypted rule stays on all messages, so a mention still reaches the phone (ADR 0254).
        let encrypted: NotificationMode = !isOneToOne && mode == .mentions ? .all : mode
        try await settings.setDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: isOneToOne, mode: encrypted.sdkMode)
        try await settings.setDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: isOneToOne, mode: mode.sdkMode)
    }
}
