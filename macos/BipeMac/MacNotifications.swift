// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import CryptoKit
import Foundation
import MatrixRustSDK
import UserNotifications

final class MacAppDelegate: NSObject, NSApplicationDelegate {
    static let mainWindowID = "main"

    /// Set before launch finishes, so a click that launched the app reaches the delegate.
    func applicationWillFinishLaunching(_ notification: Notification) {
        #if DEBUG
        if NSClassFromString("XCTestCase") != nil { return }
        #endif
        UNUserNotificationCenter.current().delegate = MacNotifications.shared
    }

    func applicationWillTerminate(_ notification: Notification) {
        MacLocalCache.clean()
    }
}

/// The owner and destination that a notification keeps (spec notification-service). Never another account's.
struct MacNotificationRoute: Equatable {
    enum Kind: String { case message, request }
    let userID: String
    let roomID: String
    let kind: Kind

    var userInfo: [String: String] { ["userID": userID, "roomID": roomID, "kind": kind.rawValue] }

    init(userID: String, roomID: String, kind: Kind) {
        self.userID = userID; self.roomID = roomID; self.kind = kind
    }

    /// A malformed payload has no route and opens nothing.
    init?(userInfo: [AnyHashable: Any]) {
        guard let userID = userInfo["userID"] as? String, let roomID = userInfo["roomID"] as? String,
              let kind = (userInfo["kind"] as? String).flatMap(Kind.init(rawValue:)) else { return nil }
        self.init(userID: userID, roomID: roomID, kind: kind)
    }

    /// An opaque account-plus-room digest. One notification per chat, per account.
    var identifier: String {
        SHA256.hash(data: Data("\(userID)|\(roomID)".utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }
}

/// The pure half of the Mac rule in spec notification-service.
enum MacNotificationRule {
    /// The room rule wins. Otherwise a DM reads its default, and a group reads the plain default (ADR 0254), because
    /// the encrypted default of a quiet group stays on all messages for the push.
    static func defaultModeQuery(isDm: Bool, isEncrypted: Bool) -> (isEncrypted: Bool, isOneToOne: Bool) {
        (isEncrypted && isDm, isDm)
    }

    static func alerts(mode: RoomNotificationMode?, newMention: Bool) -> Bool {
        switch mode {
        case .mute: false
        case .mentionsAndKeywordsOnly: newMention
        case .allMessages, nil: true
        }
    }

    /// A room seen before compares with its last incoming message. A room seen for the first time compares with the
    /// moment this account started, so history at launch stays silent.
    static func isNew(incomingMs: UInt64?, previousMs: UInt64?, startedMs: UInt64, unread: UInt64) -> Bool {
        guard unread > 0, let incomingMs else { return false }
        return incomingMs > (previousMs ?? startedMs)
    }
}

@MainActor
final class MacNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotifications()

    /// Handles a click. False keeps the route until the accounts are restored.
    var onOpen: ((MacNotificationRoute) -> Bool)?
    private var pendingOpen: MacNotificationRoute?
    private var asked = false
    private var states: [String: AccountState] = [:]

    private struct AccountState {
        let startedMs = UInt64(Date().timeIntervalSince1970 * 1000)
        var incoming: [String: UInt64] = [:]
        var mentions: [String: UInt64] = [:]
        var delivered: Set<String> = []
        var requests: Set<String> = []
        var deliveredRequests: Set<String> = []
    }

    private struct MessageAlert {
        let row: MacRoomSummary
        let newMention: Bool
    }

    func requestAuthorization() {
        guard !asked else { return }
        asked = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// Sign-out: removes this account's delivered notifications, also those of an earlier app run, and its state.
    func forget(userID: String) async {
        states[userID] = nil
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        let identifiers = delivered.filter {
            MacNotificationRoute(userInfo: $0.request.content.userInfo)?.userID == userID
        }.map(\.request.identifier)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    static func setBadge(_ accounts: [MacTabAttention]) {
        let total = accounts.reduce(0) { $0 + $1.chats + $1.groups }
        NSApp.dockTile.badgeLabel = total > 0 ? total.formatted() : nil
    }

    func update(account: MacAccount, rows: [MacRoomSummary], isOpen: (String) -> Bool) {
        let userID = account.userId
        guard !userID.isEmpty else { return }
        var state = states[userID] ?? AccountState()
        var alerts: [MessageAlert] = []
        for row in rows {
            let incoming = row.activity.latestIncomingMs
            let isNew = MacNotificationRule.isNew(incomingMs: incoming, previousMs: state.incoming[row.id],
                                                  startedMs: state.startedMs, unread: row.unread)
            let newMention = row.mentions > (state.mentions[row.id] ?? 0)
            if let incoming { state.incoming[row.id] = incoming }
            state.mentions[row.id] = row.mentions
            if isNew, !isOpen(row.id) { alerts.append(MessageAlert(row: row, newMention: newMention)) }
        }
        // Opening the chat, a read on another device, or a room that left the list removes its notification.
        let unread = Set(rows.filter(\.attention.isUnread).map(\.id))
        let open = Set(rows.map(\.id).filter(isOpen))
        let gone = state.delivered.subtracting(unread).union(state.delivered.intersection(open))
        remove(gone.map { MacNotificationRoute(userID: userID, roomID: $0, kind: .message).identifier })
        state.delivered.subtract(gone)
        state.delivered.formUnion(alerts.map(\.row.id))
        states[userID] = state
        guard let client = account.client, !alerts.isEmpty else { return }
        let media = account.media
        Task { @MainActor [weak self] in
            let settings = await client.getNotificationSettings()
            for alert in alerts {
                let row = alert.row
                var mode = row.roomMode
                if mode == nil {
                    let query = MacNotificationRule.defaultModeQuery(isDm: row.isDm, isEncrypted: row.isEncrypted)
                    mode = await settings.getDefaultRoomNotificationMode(isEncrypted: query.isEncrypted, isOneToOne: query.isOneToOne)
                }
                guard let self else { return }
                guard MacNotificationRule.alerts(mode: mode, newMention: alert.newMention) else {
                    self.states[userID]?.delivered.remove(row.id)
                    continue
                }
                await self.post(row, userID: userID, media: media)
            }
        }
    }

    /// Spec notification-service: a DM invite is chatRequest. History at launch stays silent.
    func update(account: MacAccount, requests: [MacChatRequest]) {
        let userID = account.userId
        guard !userID.isEmpty else { return }
        var state = states[userID] ?? AccountState()
        let started = Date(timeIntervalSince1970: Double(state.startedMs) / 1000)
        let new = requests.filter { !state.requests.contains($0.roomID) && ($0.invitedAt ?? .distantPast) > started }
        let ids = Set(requests.map(\.roomID))
        let gone = state.deliveredRequests.subtracting(ids)
        remove(gone.map { MacNotificationRoute(userID: userID, roomID: $0, kind: .request).identifier })
        state.deliveredRequests.subtract(gone)
        state.deliveredRequests.formUnion(new.map(\.roomID))
        state.requests = ids
        states[userID] = state
        guard let client = account.client, !new.isEmpty else { return }
        Task { @MainActor [weak self] in
            let settings = await client.getNotificationSettings()
            guard (try? await settings.isInviteForMeEnabled()) != false, let self else { return }
            for request in new {
                let content = UNMutableNotificationContent()
                content.title = BipeStrings.appName
                content.body = BipeStrings.appNotificationChatRequest(request.name)
                content.sound = .default
                self.add(content, route: MacNotificationRoute(userID: userID, roomID: request.roomID, kind: .request))
            }
        }
    }

    private func post(_ row: MacRoomSummary, userID: String, media: MacMedia) async {
        let content = UNMutableNotificationContent()
        // Undecryptable events: never show the error text in a notification.
        let preview = row.activity.previewText
        if let preview, !preview.hasSuffix(BipeStrings.appTimelineUndecryptable) {
            content.title = row.name
            content.body = preview
            if let attachment = await Self.photo(row, media: media) { content.attachments = [attachment] }
        } else {
            content.title = BipeStrings.appName
            content.body = BipeStrings.appNotificationNewMessage
        }
        content.sound = .default
        add(content, route: MacNotificationRoute(userID: userID, roomID: row.id, kind: .message))
    }

    private func add(_ content: UNMutableNotificationContent, route: MacNotificationRoute) {
        content.threadIdentifier = route.identifier
        content.userInfo = route.userInfo
        // The same identifier replaces the chat's last notification.
        let request = UNNotificationRequest(identifier: route.identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    private func remove(_ identifiers: [String]) {
        guard !identifiers.isEmpty else { return }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    /// The DM peer's or the group's photo, at most 3 s (spec notification-service, sender photo). The system moves
    /// the file into its own store. No photo: the notification still shows.
    private static func photo(_ row: MacRoomSummary, media: MacMedia) async -> UNNotificationAttachment? {
        guard row.avatar.url != nil || row.avatar.userID != nil else { return nil }
        let url = row.avatar.url, userID = row.avatar.userID
        let data = await withTaskGroup(of: Data?.self) { group -> Data? in
            group.addTask { @MainActor in
                guard let image = await media.avatar(url: url, userID: userID, size: 192), let tiff = image.tiffRepresentation else { return nil }
                return NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(3))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        guard let data else { return nil }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".png")
        do {
            try data.write(to: file, options: .atomic)
            return try UNNotificationAttachment(identifier: "photo", url: file)
        } catch {
            try? FileManager.default.removeItem(at: file)
            return nil
        }
    }

    func deliverPendingOpen() {
        guard let route = pendingOpen else { return }
        if onOpen?(route) == true { pendingOpen = nil }
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        // The rule already kept out the chat in the key window, so what arrives here shows.
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let route = MacNotificationRoute(userInfo: response.notification.request.content.userInfo)
        Task { @MainActor in
            NSApp.activate()
            if let route {
                self.pendingOpen = route
                self.deliverPendingOpen()
            }
            completionHandler()
        }
    }
}
