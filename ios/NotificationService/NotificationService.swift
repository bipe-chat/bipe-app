// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import UserNotifications

/// Spec notification-service on iOS. A push carries only the room ID, the event ID and the unread count
/// (push-registration: format event_id_only). The extension opens the account from the shared store,
/// fetches the event, decrypts it on the phone and shows the sender, the chat and the text.
final class NotificationService: UNNotificationServiceExtension {
    /// Time for the fetch. iOS gives the extension about 30 seconds.
    private static let fetchTimeout: Duration = .seconds(25)

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var task: Task<Void, Never>?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let userInfo = request.content.userInfo
        guard let roomId = userInfo[NotificationKeys.roomId] as? String,
              let eventId = userInfo[NotificationKeys.eventId] as? String else {
            deliver(Self.fallback(roomId: nil, eventId: nil, unreadCount: nil))
            return
        }
        let unreadCount = (userInfo[NotificationKeys.unreadCount] as? NSNumber)?.intValue
        task = Task { [weak self] in
            let content = await Self.content(roomId: roomId, eventId: eventId, unreadCount: unreadCount)
            self?.deliver(content)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        task?.cancel()
        deliver(Self.fallback(roomId: nil, eventId: nil, unreadCount: nil))
    }

    /// Delivers once: the first of the fetch and the timeout wins.
    private func deliver(_ content: UNNotificationContent) {
        guard let contentHandler else { return }
        self.contentHandler = nil
        contentHandler(content)
    }

    /// Action pushReceived.
    private static func content(roomId: String, eventId: String, unreadCount: Int?) async -> UNNotificationContent {
        do {
            let status = try await withNotificationTimeout(fetchTimeout) {
                let client = try await NotificationClients.shared.client()
                return try await client.getNotification(roomId: roomId, eventId: eventId)
            }
            switch status {
            case .event(let item):
                return content(item: item, roomId: roomId, eventId: eventId, unreadCount: unreadCount)
            case .eventFilteredOut, .eventRedacted, .eventNotFound:
                // Shows nothing: an empty content replaces the gateway alert.
                let empty = UNMutableNotificationContent()
                empty.badge = unreadCount.map { NSNumber(value: $0) }
                return empty
            }
        } catch {
            return fallback(roomId: roomId, eventId: eventId, unreadCount: unreadCount)
        }
    }

    private static func content(item: NotificationItem, roomId: String, eventId: String, unreadCount: Int?) -> UNNotificationContent {
        let room = item.roomInfo
        let senderName = item.senderInfo.displayName.flatMap { $0.isEmpty ? nil : $0 }
        let content = UNMutableNotificationContent()
        switch item.event {
        case .timeline(let event):
            guard let text = messageText(event) else {
                return fallback(roomId: roomId, eventId: eventId, unreadCount: unreadCount)
            }
            let sender = senderName ?? event.senderId()
            // ADR 0141: a DM shows the other person, never the room name.
            if room.isDm {
                content.title = sender
                content.body = text
            } else {
                content.title = room.displayName
                content.body = "\(sender): \(text)"
            }
            content.categoryIdentifier = NotificationKeys.messageCategory
        case .invite(let senderId):
            let sender = senderName ?? senderId
            if room.isDm {
                content.title = sender
                content.body = BipeStrings.appNotificationChatRequest(sender)
            } else {
                content.title = room.displayName
                content.body = BipeStrings.groupNotificationRoomInviteBodyWithSender(sender)
            }
        }
        content.threadIdentifier = roomId
        content.userInfo = [NotificationKeys.roomId: roomId, NotificationKeys.eventId: eventId]
        content.badge = unreadCount.map { NSNumber(value: $0) }
        content.sound = sound(isNoisy: item.isNoisy ?? true)
        return content
    }

    /// The plain text of a message. nil for an event the notification cannot show as text (media until
    /// #561, undecryptable events): those show the fallback. No Markdown (spec notes).
    private static func messageText(_ event: TimelineEvent) -> String? {
        guard case .messageLike(let content)? = try? event.content(),
              case .roomMessage(let messageType, _) = content else { return nil }
        switch messageType {
        case .text(let text): return text.body
        case .emote(let emote): return emote.body
        case .notice(let notice): return notice.body
        default: return nil
        }
    }

    /// Kind fallback: the event could not be fetched or decrypted in time.
    private static func fallback(roomId: String?, eventId: String?, unreadCount: Int?) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = BipeStrings.appName
        content.body = BipeStrings.appNotificationNewMessage
        content.badge = unreadCount.map { NSNumber(value: $0) }
        content.sound = sound(isNoisy: true)
        if let roomId, let eventId {
            content.threadIdentifier = roomId
            content.userInfo = [NotificationKeys.roomId: roomId, NotificationKeys.eventId: eventId]
        }
        return content
    }

    /// The sound of notification-settings. Bipe's soft sound has no asset yet, so soft plays the system sound.
    private static func sound(isNoisy: Bool) -> UNNotificationSound? {
        guard isNoisy else { return nil }
        switch NotificationSound.current {
        case .phone, .soft: return .default
        case .silent: return nil
        }
    }
}

/// One client per extension process: iOS reuses the process for the next push.
private actor NotificationClients {
    static let shared = NotificationClients()

    private var notificationClient: NotificationClient?

    func client() async throws -> NotificationClient {
        if let notificationClient { return notificationClient }
        guard let session = SessionStore.loadForPush() else {
            throw ClientError.Generic(msg: "no saved session", details: nil)
        }
        let client = try await AccountSession.makeClient(userId: session.userId, process: .notificationService)
        try await client.restoreSession(session: session)
        let notificationClient = try await client.notificationClient(processSetup: .multipleProcesses)
        self.notificationClient = notificationClient
        return notificationClient
    }
}
