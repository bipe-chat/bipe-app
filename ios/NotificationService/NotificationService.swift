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
    /// The notification waits at most this long for the delivered ack (spec read-receipts).
    private static let ackWait: Duration = .seconds(4)

    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var task: Task<Void, Never>?
    private var target: BipeNotificationTarget?
    private var eventId: String?
    private var accountRoute: String?

    /// The business pushes (bipe-chat/bipe#320): content-free, so the text is fixed here.
    private static let businessText: [String: (title: String, body: String)] = [
        "booking_tomorrow": (BipeStrings.pushBookingTomorrowTitle, BipeStrings.pushBookingTomorrowBody),
        "booking_soon": (BipeStrings.pushBookingSoonTitle, BipeStrings.pushBookingSoonBody),
        "booking_changed": (BipeStrings.pushBookingChangedTitle, BipeStrings.pushBookingChangedBody),
        "order_updated": (BipeStrings.pushOrderUpdatedTitle, BipeStrings.pushOrderUpdatedBody),
        "queue_called": (BipeStrings.queueCalled, BipeStrings.pushQueueCalledBody),
        "work_new_booking": (BipeStrings.pushWorkNewBookingTitle, BipeStrings.pushWorkBody),
        "work_new_order": (BipeStrings.pushWorkNewOrderTitle, BipeStrings.pushWorkBody),
        "work_cancelled": (BipeStrings.pushWorkCancelledTitle, BipeStrings.pushWorkBody),
        "work_today": (BipeStrings.pushWorkTodayTitle, BipeStrings.pushWorkBody),
    ]

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        self.contentHandler = contentHandler
        let userInfo = request.content.userInfo
        let noticeRoute = userInfo[NotificationKeys.accountRoute] as? String
        switch userInfo[NotificationKeys.pushKind] as? String {
        case NotificationKeys.deviceApprovalPush:
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.deviceApprovalKind,
                                       title: BipeStrings.appVerifyIncomingTitle, body: BipeStrings.appVerifyNudgeBody))
            return
        case NotificationKeys.recoveryStartedPush:
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.recoveryKind,
                                       title: BipeStrings.recoveryAlertTitle, body: BipeStrings.recoveryPushStartedBody))
            return
        case NotificationKeys.recoveryCompletedPush:
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.recoveryKind,
                                       title: BipeStrings.appName, body: BipeStrings.recoveryPushCompletedBody))
            return
        case let kind? where Self.businessText[kind] != nil:
            let text = Self.businessText[kind]!
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.businessKind, title: text.title, body: text.body))
            return
        case NotificationKeys.inviteJoinedPush:
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.inviteJoinedKind,
                                       title: BipeStrings.inviteJoinedPushTitle, body: BipeStrings.inviteJoinedPushBody))
            return
        case NotificationKeys.groupLinkJoinedPush:
            deliver(Self.accountNotice(route: noticeRoute, kind: NotificationKeys.groupLinkJoinedKind,
                                       title: BipeStrings.groupLinkJoinedPushTitle, body: BipeStrings.groupLinkJoinedPushBody,
                                       roomId: userInfo[NotificationKeys.roomIdPush] as? String))
            return
        default:
            break
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
                // Spec read-receipts, ack send: next to the notification, never in its way.
                let ack = Task { await sendDeliveredAck(item: item, target: target, eventId: eventId) }
                let shown = await eventContent(item: item, target: target, route: route, eventId: eventId, unreadCount: unreadCount)
                await waitAtMost(ackWait, for: ack)
                return shown
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

    private static func eventContent(item: NotificationItem, target: BipeNotificationTarget, route: String, eventId: String,
                                     unreadCount: Int?) async -> UNNotificationContent {
        let shown = content(item: item, target: target, route: route, eventId: eventId, unreadCount: unreadCount)
        // Spec notification-service notes, sender photo (bipe-chat/bipe#771): messages only, with preview on.
        guard case .timeline(let event) = item.event, shown.categoryIdentifier == NotificationKeys.messageCategory,
              NotificationPrefs.preview(for: target.accountUserId), let text = messageText(event),
              let mutable = shown.mutableCopy() as? UNMutableNotificationContent else { return shown }
        let senderName = BipeChatRowName.person(userID: event.senderId(), displayName: item.senderInfo.displayName, handle: nil)
        return await CommunicationNotification.updated(
            mutable, text: text, senderId: event.senderId(), senderName: senderName,
            senderAvatarUrl: item.senderInfo.avatarUrl, roomId: target.roomId,
            groupName: item.roomInfo.isDm ? nil : item.roomInfo.displayName, groupAvatarUrl: item.roomInfo.avatarUrl,
            ownUserId: target.accountUserId, client: await NotificationClients.shared.mediaClient(userId: target.accountUserId))
    }

    /// Spec read-receipts, ack send (ADR 0222): a DM event from the other person is delivered to this phone.
    private static func sendDeliveredAck(item: NotificationItem, target: BipeNotificationTarget, eventId: String) async {
        guard item.roomInfo.isDm, case .timeline(let event) = item.event, event.senderId() != target.accountUserId,
              let delivered = await NotificationClients.shared.delivered(userId: target.accountUserId),
              let room = try? await NotificationClients.shared.mediaClient(userId: target.accountUserId)?.getRoom(roomId: target.roomId)
        else { return }
        await delivered.send(room: room, eventId: eventId, sender: event.senderId())
    }

    /// Returns when the task ends or after `limit`, whichever comes first. The task keeps running.
    private static func waitAtMost(_ limit: Duration, for task: Task<Void, Never>) async {
        let done = AsyncStream<Void> { continuation in
            Task { await task.value; continuation.finish() }
            Task { try? await Task.sleep(for: limit); continuation.finish() }
        }
        for await _ in done {}
    }

    private static func content(item: NotificationItem, target: BipeNotificationTarget, route: String, eventId: String, unreadCount: Int?) -> UNNotificationContent {
        let room = item.roomInfo
        // People names: the real display name, else the @ the app cached in the app group, else bipe_person_no_name.
        func senderName(_ userId: String) -> String {
            BipeChatRowName.person(userID: userId, displayName: item.senderInfo.displayName, handle: nil)
        }
        let content = UNMutableNotificationContent()
        var kind = "message"
        switch item.event {
        case .timeline(let event):
            if room.isDm, let ring = ring(event: event, sender: senderName(event.senderId()), target: target, route: route, eventId: eventId) {
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
            let sender = senderName(event.senderId())
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
            let sender = senderName(senderId)
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

    /// A content-free push about the account, not a room: kind deviceApproval (bipe-chat/bipe#628, a new device waits
    /// for approval; the app's sync brings the request) or kind recovery (bipe-chat/bipe#171, a recovery started or
    /// completed; the app reads GET /v1/me/account). Nothing to fetch here. An unknown route, or an account no longer on
    /// this phone, shows nothing.
    private static func accountNotice(route: String?, kind: String, title: String, body: String, roomId: String? = nil) -> UNNotificationContent {
        guard let route, let userId = NotificationKeys.accountRoutes?.account(for: route),
              SessionStore.loadForPush(userId: userId) != nil else {
            return UNMutableNotificationContent()
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.userInfo = [NotificationKeys.accountUserId: userId, NotificationKeys.accountRoute: route,
                            NotificationKeys.kind: kind]
        // group_link_joined (bipe-chat/bipe#967): the tap opens the group.
        if let roomId { content.userInfo[NotificationKeys.roomId] = roomId }
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

    /// Spec read-receipts: the ack sender of [userId], kept with its client. Acks that this process's sync takes go
    /// into DeliveredLog for the app.
    private var deliveredAcks: DeliveredAcks?

    func delivered(userId: String) -> DeliveredAcks? {
        guard owner == userId, let client else { return nil }
        if let deliveredAcks { return deliveredAcks }
        let made = DeliveredAcks(client: client, ownUserId: userId)
        deliveredAcks = made
        return made
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
        deliveredAcks = nil
        owner = userId
        deviceId = session.deviceId
        return notificationClient
    }
}
