// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec read-receipts, delivered ack (ADR 0222): the to-device event chat.bipe.delivered with room_id and event_id.
/// Also compiled into the notification service extension (project.yml): keep it free of app-only types.
enum DeliveredAck {
    static let eventType = "chat.bipe.delivered"

    struct Received: Sendable, Equatable {
        let sender: String
        let roomId: String
        let eventId: String
    }

    /// The widget asks for these and nothing else.
    static let capabilityNames = [
        "org.matrix.msc3819.send.to_device:\(eventType)",
        "org.matrix.msc3819.receive.to_device:\(eventType)",
    ]

    static var approved: WidgetCapabilities {
        WidgetCapabilities(read: [.toDevice(eventType: eventType)], send: [.toDevice(eventType: eventType)],
                           requiresClient: false, updateDelayedEvent: false, sendDelayedEvent: false,
                           downloadFiles: false, rtcTransports: false)
    }

    static func content(roomId: String, eventId: String) -> [String: Any] {
        ["room_id": roomId, "event_id": eventId]
    }

    /// The to-device message the driver pushes to the widget: type, sender, content.
    static func parse(_ message: [String: Any]) -> Received? {
        guard message["type"] as? String == eventType,
              let sender = message["sender"] as? String,
              let content = message["content"] as? [String: Any],
              let roomId = content["room_id"] as? String, !roomId.isEmpty,
              let eventId = content["event_id"] as? String, !eventId.isEmpty else { return nil }
        return Received(sender: sender, roomId: roomId, eventId: eventId)
    }
}

/// The delivered events of each DM on this phone, kept in the app group so the notification service extension can
/// add acks that its sync takes (spec read-receipts notes: delivered state is device-local).
enum DeliveredLog {
    /// Acked event IDs kept per room. Acks from several devices can arrive out of order, so the row builder takes the
    /// newest one it finds in the timeline.
    static let keptPerRoom = 20
    /// Posted in this process after `add` stored a new ack; userInfo "roomId".
    static let changed = Notification.Name("bipe.delivered.changed")

    private static var defaults: UserDefaults { UserDefaults(suiteName: AppConfig.appGroup) ?? .standard }
    private static func key(_ userId: String) -> String { "bipe.delivered.\(userId)" }

    static func eventIds(userId: String, roomId: String) -> [String] {
        (defaults.dictionary(forKey: key(userId)) as? [String: [String]])?[roomId] ?? []
    }

    /// Adds an acked event. True when it was new.
    @discardableResult
    static func add(userId: String, roomId: String, eventId: String) -> Bool {
        var all = defaults.dictionary(forKey: key(userId)) as? [String: [String]] ?? [:]
        var ids = all[roomId] ?? []
        guard !ids.contains(eventId) else { return false }
        ids.append(eventId)
        all[roomId] = Array(ids.suffix(keptPerRoom))
        defaults.set(all, forKey: key(userId))
        NotificationCenter.default.post(name: changed, object: nil, userInfo: ["roomId": roomId])
        return true
    }
}

/// Sends and receives delivered acks for one account. The transport is the matrix-sdk-ffi widget driver with no page
/// (CallWidget), bound to a DM room: in an encrypted room the driver sends the ack with Olm and passes on only
/// encrypted to-device events. One driver receives the acks of every DM.
actor DeliveredAcks {
    /// A send or the capability approval without an answer after this long fails.
    static let timeout: Duration = .seconds(15)

    private let client: Client
    private let ownUserId: String
    private let onAck: @Sendable (DeliveredAck.Received) -> Void
    private var widget: CallWidget?
    /// The newest event acked per room in this process: at most one ack per room per batch.
    private var lastSent: [String: String] = [:]

    /// `onAck` gets each ack that passed the checks, after DeliveredLog has it.
    init(client: Client, ownUserId: String, onAck: @escaping @Sendable (DeliveredAck.Received) -> Void = { _ in }) {
        self.client = client
        self.ownUserId = ownUserId
        self.onAck = onAck
    }

    /// Starts the receiver on the first joined DM, when no receiver runs.
    func start() async {
        if let widget, await widget.isRunning() { return }
        for room in client.rooms() {
            guard let info = try? await room.roomInfo(), info.isDm, info.membership == .joined else { continue }
            await bind(room)
            return
        }
    }

    /// The running widget, or a new one on `room`.
    @discardableResult
    func bind(_ room: Room) async -> CallWidget? {
        if let widget, await widget.isRunning() { return widget }
        let made = try? CallWidget(idPrefix: "bipe-delivered", capabilityNames: DeliveredAck.capabilityNames,
                                   approved: DeliveredAck.approved) { [weak self] incoming in
            guard case .toDevice(let message) = incoming, let ack = DeliveredAck.parse(message) else { return }
            Task { await self?.receive(ack) }
        }
        guard let made else { return nil }
        widget = made
        Task { await made.start(room: room) }
        return made
    }

    /// Action ack send: the newest event that this device got in the DM from `sender`. Sends nothing for an event it
    /// acked already.
    func send(room: Room, eventId: String, sender: String) async {
        let roomId = room.id()
        guard sender != ownUserId, lastSent[roomId] != eventId, let widget = await bind(room) else { return }
        lastSent[roomId] = eventId
        do {
            // close() also ends the wait, and the send then fails.
            let timer = Task { try await Task.sleep(for: Self.timeout); await widget.close() }
            await widget.ready()
            timer.cancel()
            try await widget.sendToDevice(type: DeliveredAck.eventType,
                                          messages: [sender: ["*": DeliveredAck.content(roomId: roomId, eventId: eventId)]])
        } catch {
            // The driver stopped or did not answer: the next ack starts a new one.
            lastSent[roomId] = nil
            if self.widget === widget {
                await widget.close()
                self.widget = nil
            }
        }
    }

    /// Stops the driver.
    func stop() async {
        await widget?.close()
        widget = nil
    }

    /// Action ack receive: only from the DM's other member, for a DM with that member.
    private func receive(_ ack: DeliveredAck.Received) async {
        guard ack.sender != ownUserId,
              let room = try? client.getRoom(roomId: ack.roomId),
              let info = try? await room.roomInfo(), info.isDm,
              let member = try? await room.member(userId: ack.sender), member.membership == .join else { return }
        guard DeliveredLog.add(userId: ownUserId, roomId: ack.roomId, eventId: ack.eventId) else { return }
        onAck(ack)
    }
}

/// Spec read-receipts readReceiptsEnabled (ADR 0222): the Privacidade switch, on this phone, on by default.
enum ReadReceiptSetting {
    static let key = "bipe.settings.readReceipts"

    static var isOn: Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? true
    }

    /// A group always sends a public receipt. A DM sends a private one while the switch is off.
    static func receiptType(isDm: Bool) -> ReceiptType {
        isDm && !isOn ? .readPrivate : .read
    }

    /// The receipt type for a room; a room whose info cannot be read counts as a DM, the private choice.
    static func receiptType(room: Room) async -> ReceiptType {
        receiptType(isDm: (try? await room.roomInfo())?.isDm ?? true)
    }
}
