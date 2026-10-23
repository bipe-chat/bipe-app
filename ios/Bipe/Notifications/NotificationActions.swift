// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import UIKit
import UserNotifications

/// Receives the push token and the taps on notifications (spec notification-service, actions open, reply,
/// markAsRead). The notification service extension builds the notifications themselves.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([NotificationActions.messageCategory])
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Task { @MainActor in await PushRegistration.shared.tokenReceived(deviceToken) }
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        NSLog("Bipe push token failed")
    }

    /// The open chat shows its messages itself; other chats show a banner.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let target = NotificationKeys.target(notification.request.content.userInfo)
        let isOpen = await MainActor.run { target != nil && NotificationActions.shared.visibleRoom == target }
        return isOpen ? [] : [.banner, .list, .sound, .badge]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await NotificationActions.shared.handle(response)
    }
}

@MainActor
final class NotificationActions: ObservableObject {
    static let shared = NotificationActions()

    /// Action open: the chat that chat-list opens next.
    @Published var roomToOpen: BipeNotificationOpen?
    /// The chat on screen, so its pushes do not show a banner.
    var visibleRoom: BipeNotificationTarget?

    private var sessions: [String: AccountSession] = [:]

    nonisolated static let messageCategory = UNNotificationCategory(
        identifier: NotificationKeys.messageCategory,
        actions: [
            UNTextInputNotificationAction(identifier: NotificationKeys.replyAction,
                                          title: BipeStrings.appNotificationReply,
                                          options: [],
                                          textInputButtonTitle: BipeStrings.appNotificationReply,
                                          textInputPlaceholder: BipeStrings.appTimelineComposerPlaceholder),
            UNNotificationAction(identifier: NotificationKeys.markReadAction,
                                 title: BipeStrings.appNotificationMarkRead,
                                 options: []),
        ],
        intentIdentifiers: []
    )

    func attach(_ session: AccountSession) {
        sessions[session.userId] = session
    }

    func owns(_ session: AccountSession) -> Bool {
        sessions[session.userId] === session
    }

    func detach(userId: String) {
        sessions.removeValue(forKey: userId)
        let center = UNUserNotificationCenter.current()
        Task {
            let ids = await center.deliveredNotifications()
                .filter { NotificationKeys.recordedTarget($0.request.content.userInfo)?.accountUserId == userId }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
        if roomToOpen?.accountUserId == userId { roomToOpen = nil }
        if visibleRoom?.accountUserId == userId { visibleRoom = nil }
    }

    func handle(_ response: UNNotificationResponse) async {
        let userInfo = response.notification.request.content.userInfo
        guard let target = NotificationKeys.target(userInfo),
              let route = userInfo[NotificationKeys.accountRoute] as? String else { return }
        let eventId = userInfo[NotificationKeys.eventId] as? String
        switch response.actionIdentifier {
        case NotificationKeys.replyAction:
            guard userInfo[NotificationKeys.kind] as? String == "message" else { return }
            guard let text = (response as? UNTextInputNotificationResponse)?.userText, let eventId else { return }
            await reply(text: text, target: target, route: route, eventId: eventId)
        case NotificationKeys.markReadAction:
            guard userInfo[NotificationKeys.kind] as? String == "message" else { return }
            await markAsRead(target: target, route: route)
        case UNNotificationDefaultActionIdentifier:
            open(target: target, kind: userInfo[NotificationKeys.kind] as? String)
        default:
            break
        }
    }

    /// Action open: chat-timeline of the chat. Scrolling to the event waits for timeline focus (chat-timeline).
    func open(target: BipeNotificationTarget, kind: String?) {
        roomToOpen = BipeNotificationOpen(target: target, kind: kind)
    }

    /// Opening a chat removes its notifications (spec notification-service notes).
    func chatOpened(_ target: BipeNotificationTarget?) {
        visibleRoom = target
        if let target { removeNotifications(target: target) }
    }

    /// Action reply: sends the text as a reply to the event and marks the chat as read, without opening the app.
    private func reply(text: String, target: BipeNotificationTarget, route: String, eventId: String) async {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let backgroundTask = UIApplication.shared.beginBackgroundTask()
        defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
        var isDm = true
        var owner: AccountSession?
        do {
            guard let session = await waitForSession(userId: target.accountUserId),
                  let room = try session.client.getRoom(roomId: target.roomId) else {
                throw ClientError.Generic(msg: "no room", details: nil)
            }
            owner = session
            let info = try await room.roomInfo()
            guard info.membership == .joined, !info.isSpace else { return }
            isDm = info.isDm
            let timeline = try await room.timeline()
            let content = try messageEventContentNew(msgtype: .text(content: TextMessageContent(body: body, formatted: nil)))
            guard sessions[target.accountUserId] === session, NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            try await timeline.sendReply(msg: content, eventId: eventId)
            try await Self.waitUntilSent(timeline)
            guard sessions[target.accountUserId] === session, NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            try? await room.markAsRead(receiptType: .read)
            guard sessions[target.accountUserId] === session, NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            removeNotifications(target: target)
        } catch {
            guard let owner, sessions[target.accountUserId] === owner, NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            let failure = UNMutableNotificationContent()
            failure.body = isDm ? BipeStrings.dmInlineReplyFailed : BipeStrings.groupNotificationInlineReplyFailed
            failure.threadIdentifier = target.identifier
            failure.userInfo = NotificationKeys.payload(target, route: route, eventId: eventId)
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: failure, trigger: nil))
        }
    }

    /// Action markAsRead: a public read receipt, then the chat's notifications go away.
    private func markAsRead(target: BipeNotificationTarget, route: String) async {
        let backgroundTask = UIApplication.shared.beginBackgroundTask()
        defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
        guard let session = await waitForSession(userId: target.accountUserId),
              let room = try? session.client.getRoom(roomId: target.roomId),
              sessions[target.accountUserId] === session,
              NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
        do {
            let info = try await room.roomInfo()
            guard info.membership == .joined, !info.isSpace, sessions[target.accountUserId] === session,
                  NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            try await room.markAsRead(receiptType: .read)
            guard sessions[target.accountUserId] === session, NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId else { return }
            removeNotifications(target: target)
        } catch {
            // Keep the notification available for another attempt.
        }
    }

    private func removeNotifications(target: BipeNotificationTarget) {
        let center = UNUserNotificationCenter.current()
        Task {
            let ids = await center.deliveredNotifications()
                .filter { NotificationKeys.recordedTarget($0.request.content.userInfo) == target }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// The owner can restore after the foreground account. Never substitute that foreground session.
    private func waitForSession(userId: String) async -> AccountSession? {
        let deadline = ContinuousClock.now + .seconds(20)
        while !Task.isCancelled {
            if let session = sessions[userId] { return session }
            guard ContinuousClock.now < deadline else { return nil }
            do { try await Task.sleep(for: .milliseconds(100)) } catch { return nil }
        }
        return nil
    }

    /// The send queue sends in the background; the app stays awake until the reply left the phone.
    private static func waitUntilSent(_ timeline: Timeline) async throws {
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        let sent = try await withNotificationTimeout(.seconds(20)) {
            var items: [TimelineItem] = []
            for await diffs in stream.updates {
                diffs.forEach { items.apply($0.vectorDiff) }
                let own = items.compactMap { $0.asEvent() }.filter(\.isOwn)
                if own.contains(where: { if case .sendingFailed = $0.localSendState { true } else { false } }) {
                    return false
                }
                if !own.contains(where: { if case .notSentYet = $0.localSendState { true } else { false } }) {
                    return true
                }
            }
            return false
        }
        if !sent {
            throw ClientError.Generic(msg: "reply failed", details: nil)
        }
    }
}
