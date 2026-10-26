// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AudioToolbox
import AVFoundation
import BipeKit
import Foundation
import MatrixRustSDK
import UserNotifications

/// One ringing call of spec incoming-call.
struct IncomingCall: Identifiable, Equatable {
    var id: String { ringEventId }
    let accountUserId: String
    let roomId: String
    let ringEventId: String
    let callerName: String
    let callerAvatarUrl: String?
    let video: Bool
    let expiresAt: Date
}

/// Spec incoming-call: rings for a DM call while the app runs, then answer, decline, answered elsewhere or missed.
@MainActor
final class IncomingCalls: ObservableObject {
    static let shared = IncomingCalls()

    @Published private(set) var ringing: IncomingCall?
    private var sessions: [String: AccountSession] = [:]
    private var listeners: [String: RingListener] = [:]
    private var watch: Task<Void, Never>?
    private var roomInfoHandle: TaskHandle?
    private let tone = RingTone()

    /// Listens to the account's sync notifications for ring events.
    func watch(_ session: AccountSession) async {
        guard listeners[session.userId] == nil else { return }
        let listener = RingListener { [weak self] item, roomId in
            Task { @MainActor in self?.received(item, roomId: roomId, accountUserId: session.userId) }
        }
        sessions[session.userId] = session
        listeners[session.userId] = listener
        await session.client.registerNotificationHandler(listener: listener)
    }

    private func received(_ item: NotificationItem, roomId: String, accountUserId: String) {
        guard let session = sessions[accountUserId],
              let event = (try? JSONSerialization.jsonObject(with: Data(item.rawEvent.utf8))) as? [String: Any],
              event["type"] as? String == BipeCallRing.eventType,
              let eventId = event["event_id"] as? String, let sender = event["sender"] as? String,
              let content = event["content"] as? [String: Any] else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let origin = (event["origin_server_ts"] as? NSNumber)?.int64Value ?? now
        let read = BipeCallRing.read(content: content, sender: sender, originServerTs: origin, ownUserId: session.userId, now: now)
        guard read.ring, ringing == nil, !CallCenter.shared.isInCall(roomId: roomId) else { return }
        let call = IncomingCall(accountUserId: accountUserId, roomId: roomId, ringEventId: eventId,
                                callerName: item.senderInfo.displayName ?? sender, callerAvatarUrl: item.senderInfo.avatarUrl,
                                video: read.video, expiresAt: Date(timeIntervalSince1970: Double(read.expiresAt) / 1000))
        start(call, session: session)
    }

    private func start(_ call: IncomingCall, session: AccountSession) {
        ringing = call
        tone.start()
        let room = try? session.client.getRoom(roomId: call.roomId)
        // Answered on another device of the own user, or the caller hung up.
        roomInfoHandle = room?.subscribeToRoomInfoUpdates(listener: CallRoomInfoListener { [weak self] info in
            Task { @MainActor in self?.roomChanged(info, call: call, ownUserId: session.userId) }
        })
        watch?.cancel()
        watch = Task { [weak self] in
            let wait = max(0, call.expiresAt.timeIntervalSinceNow)
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            self?.missed(call)
        }
    }

    private func roomChanged(_ info: RoomInfo, call: IncomingCall, ownUserId: String) {
        guard ringing == call else { return }
        if info.activeRoomCallParticipants.contains(ownUserId) {
            stop()
        } else if !info.hasRoomCall {
            missed(call)
        }
    }

    func answer() {
        guard let call = ringing, let session = sessions[call.accountUserId],
              let room = try? session.client.getRoom(roomId: call.roomId) else { stop(); return }
        stop()
        Task {
            // The ringing cover goes away first; then the call cover can show.
            try? await Task.sleep(nanoseconds: 600_000_000)
            await CallCenter.shared.start(session: session, room: room, roomName: call.callerName, video: call.video)
        }
    }

    func decline() {
        guard let call = ringing, let session = sessions[call.accountUserId] else { stop(); return }
        stop()
        let room = try? session.client.getRoom(roomId: call.roomId)
        Task { try? await room?.declineCall(rtcNotificationEventId: call.ringEventId) }
    }

    private func missed(_ call: IncomingCall) {
        guard ringing == call else { return }
        stop()
        MissedCalls.record(accountUserId: call.accountUserId, roomId: call.roomId)
        MissedCalls.notify(call)
    }

    private func stop() {
        tone.stop()
        watch?.cancel()
        watch = nil
        roomInfoHandle?.cancel()
        roomInfoHandle = nil
        ringing = nil
    }
}

private final class RingListener: SyncNotificationListener, @unchecked Sendable {
    private let onItem: (NotificationItem, String) -> Void

    init(onItem: @escaping (NotificationItem, String) -> Void) {
        self.onItem = onItem
    }

    func onNotification(notification: NotificationItem, roomId: String) {
        onItem(notification, roomId)
    }
}

private final class CallRoomInfoListener: RoomInfoListener, @unchecked Sendable {
    private let onInfo: (RoomInfo) -> Void

    init(onInfo: @escaping (RoomInfo) -> Void) {
        self.onInfo = onInfo
    }

    func call(roomInfo: RoomInfo) {
        onInfo(roomInfo)
    }
}

/// The missed calls of each account, for the notification and the chat list preview (spec incoming-call timeout).
enum MissedCalls {
    private static var defaults: UserDefaults { UserDefaults(suiteName: AppConfig.appGroup) ?? .standard }

    private static func key(_ accountUserId: String) -> String { "bipe.missedCalls.\(accountUserId)" }

    static func record(accountUserId: String, roomId: String) {
        var rooms = defaults.stringArray(forKey: key(accountUserId)) ?? []
        if !rooms.contains(roomId) { rooms.append(roomId) }
        defaults.set(rooms, forKey: key(accountUserId))
    }

    static func isMissed(accountUserId: String, roomId: String) -> Bool {
        defaults.stringArray(forKey: key(accountUserId))?.contains(roomId) == true
    }

    /// The chat was opened, or a newer call came: the preview shows the call kind again.
    static func clear(accountUserId: String, roomId: String) {
        let rooms = (defaults.stringArray(forKey: key(accountUserId)) ?? []).filter { $0 != roomId }
        defaults.set(rooms, forKey: key(accountUserId))
    }

    static func notify(_ call: IncomingCall) {
        let content = UNMutableNotificationContent()
        content.title = call.callerName
        content.body = BipeStrings.callMissed
        content.threadIdentifier = call.roomId
        if let target = BipeNotificationTarget(accountUserId: call.accountUserId, roomId: call.roomId),
           let routes = NotificationKeys.accountRoutes {
            content.userInfo = NotificationKeys.payload(target, route: routes.route(for: call.accountUserId),
                                                        eventId: call.ringEventId, kind: "missedCall")
        }
        let request = UNNotificationRequest(identifier: "missed-\(call.ringEventId)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/// The in-app ring (spec incoming-call notes): Bipe's own tone, muted by the silent switch (soloAmbient), and vibration.
@MainActor
final class RingTone {
    private var player: AVAudioPlayer?
    private var vibration: Task<Void, Never>?

    func start() {
        try? AVAudioSession.sharedInstance().setCategory(.soloAmbient)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(data: Self.tone)
        player?.numberOfLoops = -1
        player?.play()
        vibration?.cancel()
        vibration = Task {
            while !Task.isCancelled {
                AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func stop() {
        player?.stop()
        player = nil
        vibration?.cancel()
        vibration = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    /// 3 seconds of 16-bit mono PCM: two short bursts of 440 and 480 Hz, then silence.
    private static let tone: Data = {
        let rate = 16_000
        var samples = [Int16](repeating: 0, count: rate * 3)
        for i in 0..<samples.count {
            let t = Double(i) / Double(rate)
            let on = t < 0.4 || (t >= 0.6 && t < 1.0)
            guard on else { continue }
            let value = 0.25 * (sin(2 * .pi * 440 * t) + sin(2 * .pi * 480 * t))
            samples[i] = Int16(value * Double(Int16.max))
        }
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        let bytes = samples.count * 2
        data.append(contentsOf: Array("RIFF".utf8)); append(UInt32(36 + bytes))
        data.append(contentsOf: Array("WAVE".utf8)); data.append(contentsOf: Array("fmt ".utf8))
        append(UInt32(16)); append(UInt16(1)); append(UInt16(1)); append(UInt32(rate)); append(UInt32(rate * 2))
        append(UInt16(2)); append(UInt16(16))
        data.append(contentsOf: Array("data".utf8)); append(UInt32(bytes))
        for sample in samples { append(sample) }
        return data
    }()
}
