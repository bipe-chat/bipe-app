// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import CallKit
import Foundation
import LiveKit
import MatrixRustSDK
import PushKit

/// Spec incoming-call on a locked phone (bipe-chat/bipe#760): CallKit for every call, PushKit for the ring that the
/// notification service extension hands over with `CXProvider.reportNewIncomingVoIPPushPayload`.
@MainActor
final class CallKitCenter: NSObject {
    static let shared = CallKitCenter()

    /// A ring reported to CallKit and not answered yet.
    private struct Ring {
        let accountUserId: String
        let roomId: String
        let eventId: String
        let callerName: String
        let video: Bool
        let expiresAt: Date
    }

    private let provider: CXProvider
    private let controller = CXCallController()
    private var registry: PKPushRegistry?
    private var rings: [UUID: Ring] = [:]
    /// The CallKit id of the running CallSession.
    private(set) var activeId: UUID?

    override init() {
        let configuration = CXProviderConfiguration()
        configuration.supportsVideo = true
        configuration.maximumCallGroups = 1
        configuration.maximumCallsPerCallGroup = 1
        configuration.supportedHandleTypes = [.generic]
        configuration.includesCallsInRecents = true
        provider = CXProvider(configuration: configuration)
        super.init()
        provider.setDelegate(self, queue: .main)
    }

    /// PushKit registration. The token goes to no pusher: the normal APNs pusher wakes the extension (ADR 0186).
    func register() {
        let registry = PKPushRegistry(queue: .main)
        registry.delegate = self
        registry.desiredPushTypes = [.voIP]
        self.registry = registry
    }

    func isRinging(eventId: String) -> Bool { rings.values.contains { $0.eventId == eventId } }

    // MARK: Incoming

    private func reportIncoming(_ payload: [AnyHashable: Any], completion: @escaping () -> Void) {
        let ring = Ring(accountUserId: payload[CallPushKeys.account] as? String ?? "",
                        roomId: payload[CallPushKeys.roomId] as? String ?? "",
                        eventId: payload[CallPushKeys.eventId] as? String ?? "",
                        callerName: payload[CallPushKeys.caller] as? String ?? BipeStrings.appName,
                        video: payload[CallPushKeys.video] as? Bool ?? false,
                        expiresAt: Date(timeIntervalSince1970: (payload[CallPushKeys.expiresAt] as? Double ?? 0) / 1000))
        let id = UUID()
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: ring.roomId)
        update.localizedCallerName = ring.callerName
        update.hasVideo = ring.video
        // Apple ends an app that gets a VoIP push and reports no call, so every push is reported, even an old one.
        provider.reportNewIncomingCall(with: id, update: update) { [weak self] error in
            Task { @MainActor in
                completion()
                guard error == nil, let self else { return }
                self.rings[id] = ring
                IncomingCalls.shared.handedToCallKit(eventId: ring.eventId)
                await self.checkStillRuns(id, ring: ring)
            }
        }
    }

    /// The call may be over before the app ran: end it as missed. Else end it when the ring lifetime ends.
    private func checkStillRuns(_ id: UUID, ring: Ring) async {
        guard ring.expiresAt > Date(), let session = await session(for: ring.accountUserId),
              let room = try? session.client.getRoom(roomId: ring.roomId) else {
            missed(id)
            return
        }
        // The first sync after a cold start may still bring the caller's membership.
        for _ in 0..<20 where !room.hasActiveRoomCall() {
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        guard room.hasActiveRoomCall() else {
            missed(id)
            return
        }
        try? await Task.sleep(nanoseconds: UInt64(max(0, ring.expiresAt.timeIntervalSinceNow) * 1_000_000_000))
        if rings[id] != nil { missed(id) }
    }

    private func missed(_ id: UUID) {
        guard let ring = rings.removeValue(forKey: id) else {
            provider.reportCall(with: id, endedAt: nil, reason: .unanswered)
            return
        }
        provider.reportCall(with: id, endedAt: nil, reason: .unanswered)
        MissedCalls.record(accountUserId: ring.accountUserId, roomId: ring.roomId)
        MissedCalls.notify(IncomingCall(accountUserId: ring.accountUserId, roomId: ring.roomId, ringEventId: ring.eventId,
                                        callerName: ring.callerName, callerAvatarUrl: nil, video: ring.video, expiresAt: ring.expiresAt))
    }

    /// The account's session, opened without a window when PushKit woke the app.
    private func session(for userId: String) async -> AccountSession? {
        let start = AccountStartModel.shared
        if start.sessions.isEmpty { await start.start() }
        for _ in 0..<20 {
            if let session = start.sessions.first(where: { $0.userId == userId }) {
                await IncomingCalls.shared.watch(session)
                return session
            }
            try? await Task.sleep(nanoseconds: 500_000_000)
        }
        return nil
    }

    // MARK: Outgoing and running calls

    /// Spec call start through CXStartCallAction, so the call shows in the status bar and in Recents.
    func startOutgoing(_ id: UUID, roomId: String, name: String, video: Bool) async -> Bool {
        let handle = CXHandle(type: .generic, value: roomId)
        let action = CXStartCallAction(call: id, handle: handle)
        action.isVideo = video
        action.contactIdentifier = name
        do {
            try await controller.request(CXTransaction(action: action))
        } catch {
            return false
        }
        let update = CXCallUpdate()
        update.remoteHandle = handle
        update.localizedCallerName = name
        update.hasVideo = video
        provider.reportCall(with: id, updated: update)
        activeId = id
        return true
    }

    func reportConnected(_ id: UUID) {
        provider.reportOutgoingCall(with: id, connectedAt: Date())
    }

    /// The app's own controls go through CallKit, so the system UI stays in step.
    func requestEnd(_ id: UUID) {
        controller.request(CXTransaction(action: CXEndCallAction(call: id))) { _ in }
    }

    func requestMute(_ id: UUID, muted: Bool) {
        controller.request(CXTransaction(action: CXSetMutedCallAction(call: id, muted: muted))) { _ in }
    }

    /// The call ended on its own (the other side, no answer, a lost connection).
    func reportEnded(_ id: UUID, reason: CXCallEndedReason) {
        guard activeId == id else { return }
        activeId = nil
        provider.reportCall(with: id, endedAt: Date(), reason: reason)
    }

    /// playAndRecord for the call; CallKit activates the session itself (didActivate).
    private static func configureAudio(video: Bool) {
        AudioManager.shared.audioSession.isAutomaticConfigurationEnabled = false
        try? AVAudioSession.sharedInstance().setCategory(.playAndRecord, mode: video ? .videoChat : .voiceChat,
                                                         options: [.allowBluetooth, .allowBluetoothA2DP])
    }
}

extension CallKitCenter: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        MainActor.assumeIsolated {
            rings = [:]
            if activeId != nil { CallCenter.shared.active?.end() }
            activeId = nil
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        MainActor.assumeIsolated {
            Self.configureAudio(video: action.isVideo)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        MainActor.assumeIsolated {
            guard let ring = rings.removeValue(forKey: action.callUUID) else {
                action.fail()
                return
            }
            Self.configureAudio(video: ring.video)
            activeId = action.callUUID
            action.fulfill()
            Task {
                guard let session = await session(for: ring.accountUserId),
                      let room = try? session.client.getRoom(roomId: ring.roomId) else {
                    reportEnded(action.callUUID, reason: .failed)
                    return
                }
                await CallCenter.shared.start(session: session, room: room, roomName: ring.callerName, video: ring.video,
                                              callKitId: action.callUUID)
            }
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        MainActor.assumeIsolated {
            if let ring = rings.removeValue(forKey: action.callUUID) {
                // Decline of a ring (MSC4310).
                Task {
                    let session = await session(for: ring.accountUserId)
                    let room = try? session?.client.getRoom(roomId: ring.roomId)
                    try? await room?.declineCall(rtcNotificationEventId: ring.eventId)
                }
            } else if activeId == action.callUUID {
                activeId = nil
                CallCenter.shared.active?.end()
            }
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        MainActor.assumeIsolated {
            CallCenter.shared.active?.setMicrophone(on: !action.isMuted)
            action.fulfill()
        }
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        MainActor.assumeIsolated {
            CallCenter.shared.active?.audioActivated()
        }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {}
}

extension CallKitCenter: PKPushRegistryDelegate {
    nonisolated func pushRegistry(_ registry: PKPushRegistry, didUpdate pushCredentials: PKPushCredentials, for type: PKPushType) {}

    nonisolated func pushRegistry(_ registry: PKPushRegistry, didReceiveIncomingPushWith payload: PKPushPayload,
                                  for type: PKPushType, completion: @escaping () -> Void) {
        let dictionary = payload.dictionaryPayload
        MainActor.assumeIsolated {
            reportIncoming(dictionary, completion: completion)
        }
    }
}
