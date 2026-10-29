// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Names the app and the notification service extension share. Also compiled into the extension (project.yml).
enum NotificationKeys {
    /// The category of a message notification, with the reply and mark-as-read actions (spec notification-service).
    static let messageCategory = "chat.bipe.message"
    static let replyAction = "chat.bipe.reply"
    static let markReadAction = "chat.bipe.markRead"

    /// Keys of the push payload: Sygnal puts them at the top level for format event_id_only.
    static let accountUserId = "account_user_id"
    static let accountRoute = "account_route"
    static var accountRoutes: BipePushAccountRoutes? {
        UserDefaults(suiteName: AppConfig.appGroup).map { BipePushAccountRoutes(defaults: $0) }
    }
    static let roomId = "room_id"
    static let eventId = "event_id"
    static let kind = "notification_kind"
    static let unreadCount = "unread_count"
    /// bipe-api adds this to the default_payload of a push that is not a room event (spec notification-service).
    static let pushKind = "bipe_push_kind"
    /// pushKind of the approval nudge (bipe-chat/bipe#628), and the kind of its local notification.
    static let deviceApprovalPush = "device_approval"
    static let deviceApprovalKind = "deviceApproval"
    /// pushKinds of the recovery alerts (bipe-chat/bipe#171), and the kind of their local notification.
    static let recoveryStartedPush = "recovery_started"
    static let recoveryCompletedPush = "recovery_completed"
    static let recoveryKind = "recovery"

    /// The owner of a local account notice (an approval nudge or a recovery alert) of `kind`, while its route still
    /// belongs to the recorded owner.
    static func noticeOwner(_ userInfo: [AnyHashable: Any], kind: String) -> String? {
        guard userInfo[Self.kind] as? String == kind,
              let userId = userInfo[accountUserId] as? String,
              let route = userInfo[accountRoute] as? String,
              accountRoutes?.account(for: route) == userId else { return nil }
        return userId
    }

    /// A provider payload carries only a random registration route; the Matrix ID stays on this phone.
    static func pushTarget(_ userInfo: [AnyHashable: Any]) -> BipeNotificationTarget? {
        guard let route = userInfo[accountRoute] as? String,
              let userId = accountRoutes?.account(for: route) else { return nil }
        return BipeNotificationTarget(accountUserId: userId, roomId: userInfo[roomId] as? String)
    }

    static func target(_ userInfo: [AnyHashable: Any]) -> BipeNotificationTarget? {
        guard let route = userInfo[accountRoute] as? String,
              let target = recordedTarget(userInfo),
              accountRoutes?.account(for: route) == target.accountUserId else { return nil }
        return target
    }

    /// For clearing this account's older delivered notices, including invalidated registrations.
    static func recordedTarget(_ userInfo: [AnyHashable: Any]) -> BipeNotificationTarget? {
        BipeNotificationTarget(accountUserId: userInfo[accountUserId] as? String,
                               roomId: userInfo[roomId] as? String)
    }

    static func payload(_ target: BipeNotificationTarget, route: String, eventId: String? = nil, kind: String = "message") -> [AnyHashable: Any] {
        var info: [AnyHashable: Any] = [accountUserId: target.accountUserId, accountRoute: route, roomId: target.roomId, Self.kind: kind]
        if let eventId { info[Self.eventId] = eventId }
        return info
    }
}

/// The message sound of one account on this phone (spec notification-settings, state sound; bipe-chat/bipe#511). A phone
/// setting, not a push rule.
enum NotificationSound: String, CaseIterable {
    case phone
    case soft
    case silent

    /// The phone-wide value from before sounds were per account: the start value of every account.
    private static let legacyKey = "notificationSound"

    static func current(for userId: String) -> NotificationSound {
        let defaults = NotificationPrefs.defaults
        let value = defaults.string(forKey: NotificationPrefs.key("sound", userId)) ?? defaults.string(forKey: legacyKey)
        return value.flatMap(NotificationSound.init(rawValue:)) ?? .phone
    }

    static func set(_ sound: NotificationSound, for userId: String) {
        NotificationPrefs.defaults.set(sound.rawValue, forKey: NotificationPrefs.key("sound", userId))
    }
}

/// Notification settings per account on this phone (bipe-chat/bipe#511), in the app group so the extension reads them.
enum NotificationPrefs {
    static var defaults: UserDefaults {
        UserDefaults(suiteName: AppConfig.appGroup) ?? .standard
    }

    static func key(_ name: String, _ userId: String) -> String {
        "notifications.\(name).\(userId)"
    }

    /// Spec notification-settings, state preview. Default true.
    static func preview(for userId: String) -> Bool {
        defaults.object(forKey: key("preview", userId)) as? Bool ?? true
    }

    static func setPreview(_ isOn: Bool, for userId: String) {
        defaults.set(isOn, forKey: key("preview", userId))
    }

    /// The last opening hours read for a work account (spec work-hours notes). `nil`: no hours, the account rings.
    static func workHours(for userId: String) -> BipeWorkHours? {
        guard let data = defaults.data(forKey: key("workHours", userId)) else { return nil }
        return try? JSONDecoder().decode(BipeWorkHours.self, from: data)
    }

    static func setWorkHours(_ hours: BipeWorkHours?, for userId: String) {
        if let hours, let data = try? JSONEncoder().encode(hours) {
            defaults.set(data, forKey: key("workHours", userId))
        } else {
            defaults.removeObject(forKey: key("workHours", userId))
        }
    }

    /// True when this account's pushes arrive quietly now: a work account outside its opening hours.
    static func isQuiet(_ userId: String, at date: Date = .now) -> Bool {
        workHours(for: userId)?.isQuiet(at: date) ?? false
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

/// The VoIP payload that the notification service extension hands to CallKit for a ring (bipe-chat/bipe#760,
/// CXProvider.reportNewIncomingVoIPPushPayload). CallKitCenter reads it in the app.
enum CallPushKeys {
    static let account = "bipe_account"
    static let roomId = "bipe_room_id"
    static let eventId = "bipe_event_id"
    static let caller = "bipe_caller"
    static let video = "bipe_video"
    static let expiresAt = "bipe_expires_at"
}
