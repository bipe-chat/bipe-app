// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import CallKit
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
    private var target: BipeNotificationTarget?
    private var eventId: String?
    private var accountRoute: String?

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let userInfo = request.content.userInfo
        if userInfo[NotificationKeys.pushKind] as? String == NotificationKeys.deviceApprovalPush {
            deliver(Self.deviceApproval(route: userInfo[NotificationKeys.accountRoute] as? String))
            return
        }
        guard let route = userInfo[NotificationKeys.accountRoute] as? String,
              let target = NotificationKeys.pushTarget(userInfo),
              let eventId = userInfo[NotificationKeys.eventId] as? String else {
            deliver(Self.fallback(target: nil, route: nil, eventId: nil, unreadCount: nil))
            return
        }
        self.accountRoute = route
        self.target = target
        self.eventId = eventId
        let unreadCount = (userInfo[NotificationKeys.unreadCount] as? NSNumber)?.intValue
        task = Task { [weak self] in
            let content = await Self.content(target: target, route: route, eventId: eventId, unreadCount: unreadCount)
            self?.deliver(content)
        }
    }

    override func serviceExtensionTimeWillExpire() {
        task?.cancel()
        let valid = accountRoute.flatMap { NotificationKeys.accountRoutes?.account(for: $0) } == target?.accountUserId
        deliver(Self.fallback(target: valid ? target : nil, route: accountRoute, eventId: eventId, unreadCount: nil))
    }

    /// Delivers once: the first of the fetch and the timeout wins.
    private func deliver(_ content: UNNotificationContent) {
        guard let contentHandler else { return }
        self.contentHandler = nil
        contentHandler(content)
    }

    /// Action pushReceived.
    private static func content(target: BipeNotificationTarget, route: String, eventId: String, unreadCount: Int?) async -> UNNotificationContent {
        do {
            let status = try await withNotificationTimeout(fetchTimeout) {
                let client = try await NotificationClients.shared.client(userId: target.accountUserId)
                return try await client.getNotification(roomId: target.roomId, eventId: eventId)
            }
            guard NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId,
                  SessionStore.loadForPush(userId: target.accountUserId) != nil else {
                return UNMutableNotificationContent()
            }
            switch status {
            case .event(let item):
                let shown = content(item: item, target: target, route: route, eventId: eventId, unreadCount: unreadCount)
                // Spec notification-service notes, sender photo (bipe-chat/bipe#771): messages only, with preview on.
                guard case .timeline(let event) = item.event, shown.categoryIdentifier == NotificationKeys.messageCategory,
                      NotificationPrefs.preview(for: target.accountUserId), let text = messageText(event),
                      let mutable = shown.mutableCopy() as? UNMutableNotificationContent else { return shown }
                let senderName = item.senderInfo.displayName.flatMap { $0.isEmpty ? nil : $0 } ?? event.senderId()
                return await CommunicationNotification.updated(
                    mutable, text: text, senderId: event.senderId(), senderName: senderName,
                    senderAvatarUrl: item.senderInfo.avatarUrl, roomId: target.roomId,
                    groupName: item.roomInfo.isDm ? nil : item.roomInfo.displayName, groupAvatarUrl: item.roomInfo.avatarUrl,
                    ownUserId: target.accountUserId, client: await NotificationClients.shared.mediaClient(userId: target.accountUserId))
            case .eventFilteredOut, .eventRedacted, .eventNotFound:
                // Shows nothing: an empty content replaces the gateway alert.
                let empty = UNMutableNotificationContent()
                empty.badge = unreadCount.map { NSNumber(value: $0) }
                return empty
            }
        } catch {
            let valid = NotificationKeys.accountRoutes?.account(for: route) == target.accountUserId
            return fallback(target: valid ? target : nil, route: route, eventId: eventId, unreadCount: unreadCount)
        }
    }

    private static func content(item: NotificationItem, target: BipeNotificationTarget, route: String, eventId: String, unreadCount: Int?) -> UNNotificationContent {
        let room = item.roomInfo
        let senderName = item.senderInfo.displayName.flatMap { $0.isEmpty ? nil : $0 }
        let content = UNMutableNotificationContent()
        var kind = "message"
        switch item.event {
        case .timeline(let event):
            if room.isDm, let ring = ring(event: event, sender: senderName ?? event.senderId(), target: target, route: route, eventId: eventId) {
                return ring
            }
            // Spec location-view notes: a live location update (MSC3672 beacon) never shows a notification.
            if case .messageLike(.beacon)? = try? event.content() {
                let empty = UNMutableNotificationContent()
                empty.badge = unreadCount.map { NSNumber(value: $0) }
                return empty
            }
            guard let text = messageText(event) else {
                return fallback(target: target, route: route, eventId: eventId, unreadCount: unreadCount)
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
            kind = room.isDm ? "chatRequest" : "groupInvite"
            let sender = senderName ?? senderId
            if room.isDm {
                content.title = sender
                content.body = BipeStrings.appNotificationChatRequest(sender)
            } else {
                content.title = room.displayName
                content.body = BipeStrings.groupNotificationRoomInviteBodyWithSender(sender)
            }
        }
        content.threadIdentifier = target.identifier
        content.userInfo = NotificationKeys.payload(target, route: route, eventId: eventId, kind: kind)
        content.badge = unreadCount.map { NSNumber(value: $0) }
        // bipe-chat/bipe#511: this account's preview choice; without it, no sender and no text.
        if !NotificationPrefs.preview(for: target.accountUserId) {
            content.title = BipeStrings.appName
            content.body = BipeStrings.appNotificationNewMessage
        }
        apply(sound: sound(isNoisy: item.isNoisy ?? true, userId: target.accountUserId), to: content, userId: target.accountUserId)
        return content
    }

    /// Spec incoming-call on a locked phone (bipe-chat/bipe#760): a DM ring for this account, inside its lifetime, goes
    /// to CallKit in the app, which rings with the system ringtone. The notification itself stays quiet.
    private static func ring(event: TimelineEvent, sender: String, target: BipeNotificationTarget, route: String,
                             eventId: String) -> UNNotificationContent? {
        guard case .messageLike(.rtcNotification(let type, let expiresAt, let intent))? = try? event.content(), type == .ring,
              event.senderId() != target.accountUserId,
              Double(expiresAt) > Date().timeIntervalSince1970 * 1000 else { return nil }
        let video = intent == .video
        let payload: [AnyHashable: Any] = [
            CallPushKeys.account: target.accountUserId,
            CallPushKeys.roomId: target.roomId,
            CallPushKeys.eventId: eventId,
            CallPushKeys.caller: sender,
            CallPushKeys.video: video,
            CallPushKeys.expiresAt: Double(expiresAt),
        ]
        CXProvider.reportNewIncomingVoIPPushPayload(payload) { _ in }
        let content = UNMutableNotificationContent()
        content.title = sender
        content.body = video ? BipeStrings.callIncomingVideo(sender) : BipeStrings.callIncomingVoice(sender)
        content.threadIdentifier = target.identifier
        content.userInfo = NotificationKeys.payload(target, route: route, eventId: eventId, kind: "call")
        content.filterCriteria = target.accountUserId
        return content
    }

    /// The account's Focus criteria and sound, and outside a work account's opening hours no sound and no banner (ADR 0138 rule 4):
    /// interruptionLevel passive keeps the notification in the list only.
    private static func apply(sound: UNNotificationSound?, to content: UNMutableNotificationContent, userId: String) {
        // bipe-chat/bipe#512: the Focus filter (AccountFocusFilter) matches this against the accounts it allows.
        content.filterCriteria = userId
        if NotificationPrefs.isQuiet(userId) {
            content.sound = nil
            content.interruptionLevel = .passive
        } else {
            content.sound = sound
        }
    }

    /// Plain text and media kind/caption from the decrypted event; no attachment fetch or raw filename.
    private static func messageText(_ event: TimelineEvent) -> String? {
        // The start of a live location share (MSC3672 beacon_info state event).
        if case .state(.beaconInfo)? = try? event.content() { return BipeStrings.appLocationLiveTitle }
        guard case .messageLike(let content)? = try? event.content() else { return nil }
        switch content {
        case .sticker: return BipeStrings.appMediaSticker
        case .roomMessage(let type, _):
            switch type {
            case .text(let value): return BipeNotificationPreview.text(value.body)
            case .emote(let value): return BipeNotificationPreview.text(value.body)
            case .notice(let value): return BipeNotificationPreview.text(value.body)
            case .image(let value): return BipeNotificationPreview.attachment(label: BipeStrings.appMediaImage, caption: value.caption)
            case .video(let value): return BipeNotificationPreview.attachment(label: BipeStrings.appMediaVideo, caption: value.caption)
            case .file(let value): return BipeNotificationPreview.attachment(label: BipeStrings.appMediaFile, caption: value.caption)
            case .audio(let value): return BipeNotificationPreview.attachment(label: value.voice != nil ? BipeStrings.appMediaVoice : BipeStrings.appMediaFile, caption: value.caption)
            case .location: return BipeStrings.appLocationTitle
            default: return nil
            }
        default: return nil
        }
    }

    /// Kind deviceApproval (bipe-chat/bipe#628): a new device of the account waits for approval. Nothing to fetch: the
    /// app's sync brings the request. An unknown route, or an account no longer on this phone, shows nothing.
    private static func deviceApproval(route: String?) -> UNNotificationContent {
        guard let route, let userId = NotificationKeys.accountRoutes?.account(for: route),
              SessionStore.loadForPush(userId: userId) != nil else {
            return UNMutableNotificationContent()
        }
        let content = UNMutableNotificationContent()
        content.title = BipeStrings.appVerifyIncomingTitle
        content.body = BipeStrings.appVerifyNudgeBody
        content.userInfo = [NotificationKeys.accountUserId: userId, NotificationKeys.accountRoute: route,
                            NotificationKeys.kind: NotificationKeys.deviceApprovalKind]
        apply(sound: sound(isNoisy: true, userId: userId), to: content, userId: userId)
        return content
    }

    /// Kind fallback: the event could not be fetched or decrypted in time.
    private static func fallback(target: BipeNotificationTarget?, route: String?, eventId: String?, unreadCount: Int?) -> UNNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = BipeStrings.appName
        content.body = BipeStrings.appNotificationNewMessage
        content.badge = unreadCount.map { NSNumber(value: $0) }
        if let userId = target?.accountUserId {
            apply(sound: sound(isNoisy: true, userId: userId), to: content, userId: userId)
        } else {
            content.sound = .default
        }
        if let target, let route, let eventId {
            content.threadIdentifier = target.identifier
            content.userInfo = NotificationKeys.payload(target, route: route, eventId: eventId, kind: "fallback")
        }
        return content
    }

    /// The sound of notification-settings. Bipe's soft sound has no asset yet, so soft plays the system sound.
    private static func sound(isNoisy: Bool, userId: String) -> UNNotificationSound? {
        guard isNoisy else { return nil }
        switch NotificationSound.current(for: userId) {
        case .phone, .soft: return .default
        case .silent: return nil
        }
    }
}

/// One client per extension process: iOS reuses the process for the next push.
private actor NotificationClients {
    static let shared = NotificationClients()

    private var notificationClient: NotificationClient?
    /// The client behind it, for the sender photos (bipe-chat/bipe#771).
    private var client: Client?
    private var owner: String?
    private var deviceId: String?

    /// The cached client of [userId]; nil before the first notification of that account.
    func mediaClient(userId: String) -> Client? {
        owner == userId ? client : nil
    }

    func client(userId: String) async throws -> NotificationClient {
        guard let session = SessionStore.loadForPush(userId: userId) else {
            throw ClientError.Generic(msg: "no saved session", details: nil)
        }
        if owner == userId, deviceId == session.deviceId, let notificationClient { return notificationClient }
        // The keychain outlives a deleted app, the store does not. Restoring the saved session into a new, empty store
        // makes a second encryption identity for the old device ID, and that device then cannot receive keys. Only the
        // app signs in again (AccountSession.restore, noStore).
        guard AccountSession.hasStore(userId: session.userId) else {
            throw ClientError.Generic(msg: "no store for the saved session", details: nil)
        }
        let client = try await AccountSession.makeClient(userId: session.userId, process: .notificationService, homeserverURL: session.homeserverUrl)
        try await client.restoreSession(session: session)
        let notificationClient = try await client.notificationClient(processSetup: .multipleProcesses)
        self.notificationClient = notificationClient
        self.client = client
        owner = userId
        deviceId = session.deviceId
        return notificationClient
    }
}
