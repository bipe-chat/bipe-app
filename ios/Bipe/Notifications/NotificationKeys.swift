// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Names the app and the notification service extension share. Also compiled into the extension (project.yml).
enum NotificationKeys {
    /// The category of a message notification, with the reply and mark-as-read actions (spec notification-service).
    static let messageCategory = "chat.bipe.message"
    static let replyAction = "chat.bipe.reply"
    static let markReadAction = "chat.bipe.markRead"

    /// Keys of the push payload: Sygnal puts them at the top level for format event_id_only.
    static let roomId = "room_id"
    static let eventId = "event_id"
    static let unreadCount = "unread_count"
}

/// The message sound on this phone (spec notification-settings, state sound). A phone setting, not a push rule.
enum NotificationSound: String, CaseIterable {
    case phone
    case soft
    case silent

    private static let key = "notificationSound"

    /// The app group defaults, so the extension reads what the app saved.
    private static var defaults: UserDefaults {
        UserDefaults(suiteName: AppConfig.appGroup) ?? .standard
    }

    static var current: NotificationSound {
        get { defaults.string(forKey: key).flatMap(NotificationSound.init(rawValue:)) ?? .phone }
        set { defaults.set(newValue.rawValue, forKey: key) }
    }
}

struct NotificationTimeout: Error {}

/// Runs an operation and throws NotificationTimeout when it takes longer than the timeout.
func withNotificationTimeout<T: Sendable>(_ timeout: Duration, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask {
            try await Task.sleep(for: timeout)
            throw NotificationTimeout()
        }
        defer { group.cancelAll() }
        guard let result = try await group.next() else { throw NotificationTimeout() }
        return result
    }
}
