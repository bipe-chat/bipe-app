// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Foundation
import LiveKit
import MatrixRustSDK

/// One member device on the call screen (type CallMember of spec call).
struct CallTile: Identifiable, Equatable {
    var id: String
    var userId: String
    var name: String
    var avatarUrl: String?
    var speaking: Bool
    var microphoneOn: Bool
    var cameraOn: Bool
    var track: VideoTrack?

    static func == (lhs: CallTile, rhs: CallTile) -> Bool {
        lhs.id == rhs.id && lhs.name == rhs.name && lhs.avatarUrl == rhs.avatarUrl && lhs.speaking == rhs.speaking
            && lhs.microphoneOn == rhs.microphoneOn && lhs.cameraOn == rhs.cameraOn && lhs.track === rhs.track
    }
}

/// Spec call (bipe-chat/bipe#758, ADR 0186): one MatrixRTC call of one room. Membership with a delayed leave
/// (MSC4143, MSC4140), the SFU token (MSC4195), LiveKit media with a per-participant key for each device.
@MainActor
final class CallSession: ObservableObject, Identifiable {
    enum Phase: Equatable {
        case connecting
        case calling
        case connected
        case reconnecting
        case ended
        case failed
    }

    /// The most video tiles on the screen (ADR 0186). More members show as a list.
    static let maxTiles = 8

    let id = UUID()
    let roomId: String
    let video: Bool
    @Published private(set) var roomName: String
    @Published private(set) var phase = Phase.connecting
    @Published private(set) var tiles: [CallTile] = []
    @Published private(set) var microphoneOn = true
    @Published private(set) var cameraOn = false
    @Published private(set) var frontCamera = true
    @Published private(set) var speakerOn: Bool
    @Published private(set) var microphoneDenied = false
    @Published private(set) var cameraDenied = false
    @Published private(set) var connectedAt: Date?
    @Published private(set) var localTrack: VideoTrack?

    private let session: AccountSession
    private let room: MatrixRustSDK.Room
    private let focusUrl: String
    private let userId: String
    private let deviceId: String
    private var widget: CallWidget?
    private var media: LiveKit.Room?
    private lazy var mediaDelegate = CallMediaDelegate(session: self)
    private let keys = BaseKeyProvider(options: KeyProviderOptions(sharedKey: false,
        ratchetWindowSize: BipeCall.ratchetWindowSize, keyRingSize: BipeCall.keyRingSize, keyDerivationAlgorithm: .hkdf))
    /// Active memberships by state key, the own one included.
    private var members: [String: BipeCallMember] = [:]
    private var names: [String: (name: String, avatarUrl: String?)] = [:]
    private var ownKey = Data()
    private var delayId: String?
    private var restartTask: Task<Void, Never>?
    private var joined = false
    private var stateSeen = false
    private var leaving = false
    /// Called once the call is over, so CallCenter can drop it.
    var onFinished: (() -> Void)?

    var ownStateKey: String { BipeCall.stateKey(userId: userId, deviceId: deviceId) }
    var ownIdentity: String { BipeCall.livekitIdentity(userId: userId, deviceId: deviceId) }
    /// Members after the first `maxTiles`, for the list.
    var extraMembers: [CallTile] { Array(tiles.dropFirst(Self.maxTiles)) }

    init(session: AccountSession, room: MatrixRustSDK.Room, roomName: String, focusUrl: String, video: Bool) throws {
        self.session = session
        self.room = room
        roomId = room.id()
        self.roomName = roomName
        self.focusUrl = focusUrl
        self.video = video
        userId = session.userId
        deviceId = try session.client.deviceId()
        speakerOn = video
    }

    // MARK: Join

    func start() async {
        guard await AVAudioApplication.requestRecordPermission() else {
            microphoneDenied = true
            phase = .failed
            return
        }
        var withCamera = video
        if video, !(await AVCaptureDevice.requestAccess(for: .video)) {
            cameraDenied = true
            withCamera = false
        }
        do {
            try await join(withCamera: withCamera)
        } catch {
            await finish(failed: true)
        }
    }

    private func join(withCamera: Bool) async throws {
        let widget = try CallWidget(userId: userId, deviceId: deviceId) { [weak self] incoming in
            Task { @MainActor in self?.receive(incoming) }
        }
        self.widget = widget
        await widget.start(room: room)
        await widget.ready()
        // The driver sends the room's call state right after the capabilities: wait for it, so the SFU choice sees the others.
        for _ in 0..<30 where !stateSeen { try? await Task.sleep(nanoseconds: 100_000_000) }

        // Delayed leave first, so a crash after the join still ends the membership (MSC4140).
        let delayed = try await widget.sendEvent(type: BipeCall.memberEventType, stateKey: ownStateKey, content: [:],
                                                 delayMs: BipeCall.leaveDelayMs)
        delayId = delayed["delay_id"] as? String
        startRestarting()
        let created = Int64(Date().timeIntervalSince1970 * 1000)
        try await widget.sendEvent(type: BipeCall.memberEventType, stateKey: ownStateKey,
            content: BipeCall.membershipContent(deviceId: deviceId, roomId: roomId, focusUrl: focusUrl, createdTs: created, video: video))
        joined = true

        ownKey = Self.randomKey()
        keys.setKey(keyData: ownKey, participantId: ownIdentity, index: BipeCall.ownKeyIndex)
        await sendOwnKey()

        let others = members.filter { $0.key != ownStateKey }.map(\.value)
        let sfu = BipeCall.oldestFocus(members: others, own: focusUrl)
        let (url, jwt) = try await token(sfu: sfu)

        AudioManager.shared.audioSession.isSpeakerOutputPreferred = speakerOn
        let media = LiveKit.Room(delegate: mediaDelegate, roomOptions: RoomOptions(adaptiveStream: true, dynacast: true,
                                                                                   e2eeOptions: E2EEOptions(keyProvider: keys)))
        self.media = media
        try await media.connect(url: url, token: jwt)
        try await media.localParticipant.setMicrophone(enabled: true)
        if withCamera {
            try await media.localParticipant.setCamera(enabled: true)
            cameraOn = true
        }
        connectedAt = Date()
        refresh()
    }

    private func token(sfu: String) async throws -> (String, String) {
        let openId = try await session.client.requestOpenidToken()
        let body = BipeCall.sfuRequestBody(roomId: roomId, deviceId: deviceId,
            openId: BipeCallOpenId(accessToken: openId.accessToken, tokenType: openId.tokenType,
                                   matrixServerName: openId.matrixServerName, expiresInSeconds: openId.expiresInSeconds))
        guard let endpoint = URL(string: sfu)?.appending(path: "sfu/get") else { throw CallWidget.Failure(message: "focus") }
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let answer = BipeCall.sfuAnswer(json) else { throw CallWidget.Failure(message: "sfu") }
        return answer
    }

    private func startRestarting() {
        restartTask?.cancel()
        restartTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: BipeCall.leaveRestartSeconds * 1_000_000_000)
                guard let self, let widget = self.widget, let delayId = self.delayId else { return }
                try? await widget.updateDelayed(delayId: delayId, action: "restart")
            }
        }
    }

    // MARK: Controls

    func toggleMicrophone() {
        guard let media else { return }
        let on = !microphoneOn
        microphoneOn = on
        Task { try? await media.localParticipant.setMicrophone(enabled: on) }
    }

    func toggleCamera() {
        guard let media else { return }
        if !cameraOn, cameraDenied { return }
        let on = !cameraOn
        cameraOn = on
        Task {
            try? await media.localParticipant.setCamera(enabled: on)
            refresh()
        }
    }

    func flipCamera() {
        guard cameraOn, let track = media?.localParticipant.firstCameraVideoTrack as? LocalVideoTrack,
              let camera = track.capturer as? CameraCapturer else { return }
        Task {
            _ = try? await camera.switchCameraPosition()
            frontCamera = camera.position != .back
        }
    }

    func toggleSpeaker() {
        speakerOn.toggle()
        AudioManager.shared.audioSession.isSpeakerOutputPreferred = speakerOn
    }

    func end() {
        Task { await finish(failed: false) }
    }

    private func finish(failed: Bool) async {
        guard !leaving else { return }
        leaving = true
        restartTask?.cancel()
        if let media {
            await media.localParticipant.unpublishAll()
            await media.disconnect()
        }
        media = nil
        if let widget {
            if joined { try? await widget.sendEvent(type: BipeCall.memberEventType, stateKey: ownStateKey, content: [:]) }
            if let delayId { try? await widget.updateDelayed(delayId: delayId, action: "cancel") }
            await widget.close()
        }
        widget = nil
        if !microphoneDenied { phase = failed ? .failed : .ended }
        if !failed {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            onFinished?()
        }
    }

    // MARK: Matrix side

    private func receive(_ incoming: CallWidget.Incoming) {
        switch incoming {
        case .state(let events):
            stateSeen = true
            updateMembers(events)
        case .event(let event):
            if event["type"] as? String == BipeCall.memberEventType, event["state_key"] != nil {
                updateMembers([event])
            } else if event["type"] as? String == BipeCall.keyEventType {
                takeKeys(sender: event["sender"] as? String, content: event["content"] as? [String: Any])
            }
        case .toDevice(let message):
            if message["type"] as? String == BipeCall.keyEventType {
                takeKeys(sender: message["sender"] as? String, content: message["content"] as? [String: Any])
            }
        }
    }

    private func updateMembers(_ events: [[String: Any]]) {
        let before = Set(members.keys)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for event in events {
            guard event["type"] as? String == BipeCall.memberEventType, let stateKey = event["state_key"] as? String,
                  let sender = event["sender"] as? String else { continue }
            let ts = (event["origin_server_ts"] as? NSNumber)?.int64Value ?? now
            if let member = BipeCall.member(sender: sender, stateKey: stateKey, originServerTs: ts,
                                            content: event["content"] as? [String: Any] ?? [:], now: now) {
                members[stateKey] = member
            } else {
                members.removeValue(forKey: stateKey)
            }
        }
        let after = Set(members.keys)
        guard joined, !leaving else { refresh(); return }
        if !after.subtracting(before).subtracting([ownStateKey]).isEmpty {
            Task { await sendOwnKey() }
        }
        if !before.subtracting(after).subtracting([ownStateKey]).isEmpty {
            Task { await rotateOwnKey() }
        }
        refresh()
    }

    private func takeKeys(sender: String?, content: [String: Any]?) {
        guard let sender, let content else { return }
        for key in BipeCall.readKeys(sender: sender, content: content) where key.identity != ownIdentity {
            keys.setKey(keyData: key.key, participantId: key.identity, index: key.index)
        }
    }

    private func sendOwnKey() async {
        guard let widget else { return }
        let content = BipeCall.keyEventContent(index: BipeCall.ownKeyIndex, key: ownKey, deviceId: deviceId,
                                               sentTs: Int64(Date().timeIntervalSince1970 * 1000))
        try? await widget.sendEvent(type: BipeCall.keyEventType, content: content)
    }

    /// A member left: a new key, so the leaver cannot follow the rest of the call.
    private func rotateOwnKey() async {
        let next = Self.randomKey()
        ownKey = next
        await sendOwnKey()
        try? await Task.sleep(nanoseconds: UInt64(BipeCall.keyUseDelaySeconds * 1_000_000_000))
        guard ownKey == next else { return }
        keys.setKey(keyData: next, participantId: ownIdentity, index: BipeCall.ownKeyIndex)
    }

    private static func randomKey() -> Data {
        var bytes = [UInt8](repeating: 0, count: BipeCall.keyLength)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }

    // MARK: Screen state

    fileprivate func mediaChanged() { refresh() }

    fileprivate func mediaConnection(_ state: LiveKit.ConnectionState) {
        guard !leaving else { return }
        switch state {
        case .reconnecting: phase = .reconnecting
        case .connected: refresh()
        default: break
        }
    }

    fileprivate func mediaDisconnected() {
        guard !leaving else { return }
        Task { await finish(failed: connectedAt == nil) }
    }

    private func refresh() {
        let others = members.filter { $0.key != ownStateKey }.map(\.value).sorted { $0.createdTs < $1.createdTs }
        let remote = media?.remoteParticipants ?? [:]
        tiles = others.map { member in
            let participant = remote[LiveKit.Participant.Identity(from: member.identity)]
            let known = names[member.userId]
            return CallTile(id: member.identity, userId: member.userId, name: known?.name ?? member.userId,
                            avatarUrl: known?.avatarUrl, speaking: participant?.isSpeaking ?? false,
                            microphoneOn: participant?.isMicrophoneEnabled() ?? true,
                            cameraOn: participant?.isCameraEnabled() ?? false, track: participant?.firstCameraVideoTrack)
        }
        localTrack = cameraOn ? media?.localParticipant.firstCameraVideoTrack : nil
        loadNames(for: others.map(\.userId))
        guard !leaving, connectedAt != nil, media?.connectionState != LiveKit.ConnectionState.reconnecting else { return }
        phase = others.isEmpty ? .calling : .connected
    }

    private func loadNames(for userIds: [String]) {
        let missing = Set(userIds).filter { names[$0] == nil }
        guard !missing.isEmpty else { return }
        for id in missing { names[id] = (id, nil) }
        let room = room
        Task {
            for id in missing {
                let name = (try? await room.memberDisplayName(userId: id)) ?? nil
                let avatar = (try? await room.memberAvatarUrl(userId: id)) ?? nil
                names[id] = (name ?? id, avatar)
            }
            refresh()
        }
    }
}

/// LiveKit calls back on its own queues; this hops to the main actor.
private final class CallMediaDelegate: NSObject, RoomDelegate, @unchecked Sendable {
    weak var session: CallSession?

    init(session: CallSession) {
        self.session = session
    }

    private func changed() {
        Task { @MainActor [weak self] in self?.session?.mediaChanged() }
    }

    func room(_ room: LiveKit.Room, didUpdateConnectionState connectionState: LiveKit.ConnectionState,
              from oldConnectionState: LiveKit.ConnectionState) {
        Task { @MainActor [weak self] in self?.session?.mediaConnection(connectionState) }
    }

    func room(_ room: LiveKit.Room, didDisconnectWithError error: LiveKitError?) {
        Task { @MainActor [weak self] in self?.session?.mediaDisconnected() }
    }

    func room(_ room: LiveKit.Room, didStartReconnectWithMode reconnectMode: ReconnectMode) {
        Task { @MainActor [weak self] in self?.session?.mediaConnection(.reconnecting) }
    }

    func room(_ room: LiveKit.Room, didCompleteReconnectWithMode reconnectMode: ReconnectMode) {
        Task { @MainActor [weak self] in self?.session?.mediaConnection(.connected) }
    }

    func room(_ room: LiveKit.Room, participantDidConnect participant: RemoteParticipant) { changed() }
    func room(_ room: LiveKit.Room, participantDidDisconnect participant: RemoteParticipant) { changed() }
    func room(_ room: LiveKit.Room, didUpdateSpeakingParticipants participants: [LiveKit.Participant]) { changed() }
    func room(_ room: LiveKit.Room, participant: RemoteParticipant, didSubscribeTrack publication: RemoteTrackPublication) { changed() }
    func room(_ room: LiveKit.Room, participant: RemoteParticipant, didUnsubscribeTrack publication: RemoteTrackPublication) { changed() }
    func room(_ room: LiveKit.Room, participant: LiveKit.Participant, trackPublication: TrackPublication, didUpdateIsMuted isMuted: Bool) { changed() }
    func room(_ room: LiveKit.Room, participant: LocalParticipant, didPublishTrack publication: LocalTrackPublication) { changed() }
}
