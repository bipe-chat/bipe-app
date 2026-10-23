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
        let roomId = notification.request.content.userInfo[NotificationKeys.roomId] as? String
        let isOpen = await MainActor.run { roomId != nil && NotificationActions.shared.visibleRoomId == roomId }
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
    @Published var roomToOpen: String?
    /// The chat on screen, so its pushes do not show a banner.
    var visibleRoomId: String?

    private var session: AccountSession?
    private var sessionWaiters: [CheckedContinuation<AccountSession?, Never>] = []

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
        self.session = session
        sessionWaiters.forEach { $0.resume(returning: session) }
        sessionWaiters.removeAll()
    }

    func handle(_ response: UNNotificationResponse) async {
        let userInfo = response.notification.request.content.userInfo
        guard let roomId = userInfo[NotificationKeys.roomId] as? String else { return }
        let eventId = userInfo[NotificationKeys.eventId] as? String
        switch response.actionIdentifier {
        case NotificationKeys.replyAction:
            guard let text = (response as? UNTextInputNotificationResponse)?.userText, let eventId else { return }
            await reply(text: text, roomId: roomId, eventId: eventId)
        case NotificationKeys.markReadAction:
            await markAsRead(roomId: roomId)
        case UNNotificationDefaultActionIdentifier:
            open(roomId: roomId)
        default:
            break
        }
    }

    /// Action open: chat-timeline of the chat. Scrolling to the event waits for timeline focus (chat-timeline).
    func open(roomId: String) {
        roomToOpen = roomId
        removeNotifications(roomId: roomId)
    }

    /// Opening a chat removes its notifications (spec notification-service notes).
    func chatOpened(_ roomId: String?) {
        visibleRoomId = roomId
        if let roomId {
            removeNotifications(roomId: roomId)
        }
    }

    /// Action reply: sends the text as a reply to the event and marks the chat as read, without opening the app.
    private func reply(text: String, roomId: String, eventId: String) async {
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !body.isEmpty else { return }
        let backgroundTask = UIApplication.shared.beginBackgroundTask()
        defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
        var isDm = true
        do {
            guard let session = await waitForSession(), let room = try session.client.getRoom(roomId: roomId) else {
                throw ClientError.Generic(msg: "no room", details: nil)
            }
            isDm = (try? await room.roomInfo().isDm) ?? true
            let timeline = try await room.timeline()
            let content = try messageEventContentNew(msgtype: .text(content: TextMessageContent(body: body, formatted: nil)))
            try await timeline.sendReply(msg: content, eventId: eventId)
            try await Self.waitUntilSent(timeline)
            try? await room.markAsRead(receiptType: .read)
            removeNotifications(roomId: roomId)
        } catch {
            let failure = UNMutableNotificationContent()
            failure.body = isDm ? BipeStrings.dmInlineReplyFailed : BipeStrings.groupNotificationInlineReplyFailed
            failure.threadIdentifier = roomId
            failure.userInfo = [NotificationKeys.roomId: roomId, NotificationKeys.eventId: eventId]
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: failure, trigger: nil))
        }
    }

    /// Action markAsRead: a public read receipt, then the chat's notifications go away.
    private func markAsRead(roomId: String) async {
        let backgroundTask = UIApplication.shared.beginBackgroundTask()
        defer { UIApplication.shared.endBackgroundTask(backgroundTask) }
        if let session = await waitForSession(), let room = try? session.client.getRoom(roomId: roomId) {
            try? await room.markAsRead(receiptType: .read)
        }
        removeNotifications(roomId: roomId)
    }

    private func removeNotifications(roomId: String) {
        let center = UNUserNotificationCenter.current()
        Task {
            let ids = await center.deliveredNotifications()
                .filter { $0.request.content.threadIdentifier == roomId }
                .map(\.request.identifier)
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
    }

    /// An action can start the app in the background: the account opens through account-start.
    private func waitForSession() async -> AccountSession? {
        if let session { return session }
        let timeout = Task { [weak self] in
            guard (try? await Task.sleep(for: .seconds(20))) != nil else { return }
            self?.attachTimedOut()
        }
        defer { timeout.cancel() }
        return await withCheckedContinuation { sessionWaiters.append($0) }
    }

    private func attachTimedOut() {
        sessionWaiters.forEach { $0.resume(returning: nil) }
        sessionWaiters.removeAll()
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
