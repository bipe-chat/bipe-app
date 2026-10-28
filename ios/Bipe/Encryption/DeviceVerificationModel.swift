// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import UIKit

/// Type VerificationSymbol of spec device-verification.
struct VerificationSymbol: Identifiable, Equatable {
    var id: Int
    var symbol: String
    var label: String
}

/// Type RequestingDevice of spec device-verification.
struct RequestingDevice: Equatable {
    var deviceId: String
    var displayName: String?
    var firstSeenAt: Date?
}

/// Spec device-verification (bipe-shared product/screens/device-verification.json).
/// Lives for the whole session, so a request from a new phone reaches this device on any screen.
@MainActor
final class DeviceVerificationModel: ObservableObject {
    enum Role: Equatable {
        case newPhone
        case approver
    }

    enum Phase: Equatable {
        case requesting
        case waitingForOther
        case incoming
        case confirmIdentity
        case compare
        case waitingForMatch
        case done
        case cancelled
        case failed
    }

    @Published private(set) var role: Role = .newPhone
    /// No flow runs yet: the idle model counts as finished (isFinished).
    @Published private(set) var phase: Phase = .cancelled {
        didSet { holdBackgroundTime() }
    }
    @Published private(set) var otherDevice: RequestingDevice?
    @Published private(set) var symbols: [VerificationSymbol] = []
    /// The flow ended with noMatch: the screen shows bipe_app_verify_mismatch.
    @Published private(set) var mismatch = false
    /// Action notifyAgain is open: newPhone waits and the last nudge is approvalNudgeAgainSeconds old.
    @Published private(set) var canNotifyAgain = false
    /// approver: the cooldown before the approve button (rule 5, the same wait as the QR approval).
    @Published private(set) var secondsLeft = 0
    /// approver: the approximate place of the requesting device, from its last IP (rule 5). placeKnown: the
    /// look-up ended; place nil then means unknown.
    @Published private(set) var place: String?
    @Published private(set) var placeKnown = false
    /// The Bipe PIN sheet of the owner check, for a phone with no biometrics (bipe-chat/bipe#785).
    let pinPrompt = AccountPinPrompt()

    /// Called when a request from another device of this account arrives (action receive).
    var onIncoming: (@MainActor () -> Void)?

    private let session: AccountSession
    private let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    private var nudgeWait: Task<Void, Never>?
    private var countdown: Task<Void, Never>?
    private var placeLookUp: Task<Void, Never>?
    private let delegate = SessionVerificationStream()
    private var controller: SessionVerificationController?
    private var started = false
    /// Background time while a flow runs (bipe-chat/bipe#794). iOS suspends the app under 1 s after it
    /// leaves the foreground; without this task the SAS key, MAC and done to-device sends and the sync stop,
    /// and the other device cancels. The sync service keeps running while the process runs.
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid
    private var activeObserver: NSObjectProtocol?

    init(session: AccountSession) {
        self.session = session
        // An expired task ends; the next foreground takes a new one while the flow still runs.
        activeObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                                                object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.holdBackgroundTime() }
        }
    }

    deinit {
        if let activeObserver { NotificationCenter.default.removeObserver(activeObserver) }
    }

    /// The flow has ended or has not started; a new request may begin.
    var isFinished: Bool {
        switch phase {
        case .done, .cancelled, .failed: true
        default: false
        }
    }

    /// Gets the controller and listens to its delegate for the life of the session.
    func start() async {
        guard !started else { return }
        started = true
        // The controller needs the account's own identity; the SDK has it after the first sync.
        await session.client.encryption().waitForE2eeInitializationTasks()
        var controller: SessionVerificationController?
        for attempt in 0..<10 {
            controller = try? await session.client.getSessionVerificationController()
            if controller != nil || Task.isCancelled { break }
            try? await Task.sleep(for: .seconds(min(2 << attempt, 30)))
        }
        guard let controller else { return }
        controller.setDelegate(delegate: delegate)
        self.controller = controller
        for await event in delegate.events {
            await handle(event)
        }
    }

    /// Action request: newPhone asks the other devices of the account.
    func request() async {
        role = .newPhone
        otherDevice = nil
        symbols = []
        mismatch = false
        canNotifyAgain = false
        phase = .requesting
        guard let controller = await readyController() else {
            phase = .failed
            return
        }
        do {
            try await controller.requestDeviceVerification()
            phase = .waitingForOther
        } catch {
            phase = .failed
            return
        }
        await nudge()
    }

    /// Action notifyAgain: the push to the other devices once more (bipe-chat/bipe#628).
    func notifyAgain() async {
        guard role == .newPhone, phase == .waitingForOther, canNotifyAgain else { return }
        await nudge()
    }

    /// POST /v1/me/approval-nudge. Best effort: a failure, rate_limited included, does not change the phase.
    private func nudge() async {
        canNotifyAgain = false
        nudgeWait?.cancel()
        let session = self.session
        let api = self.api
        try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
            try await api.sendApprovalNudge(accessToken: token)
        }
        nudgeWait = Task { [weak self] in
            try? await Task.sleep(for: .seconds(BipeRegistries.approvalNudgeAgainSeconds))
            guard !Task.isCancelled, let self, self.role == .newPhone, self.phase == .waitingForOther else { return }
            self.canNotifyAgain = true
        }
    }

    /// The approve button is on: the cooldown is over.
    var canApprove: Bool { secondsLeft == 0 }

    /// Action accept: approver waits through the cooldown, passes the owner check (ApprovalCheck: biometrics, else
    /// the Bipe PIN; never the passcode), then accepts. A phone with no screen lock cannot approve.
    func accept() async {
        guard role == .approver, phase == .incoming, canApprove, let controller else { return }
        phase = .confirmIdentity
        let session = self.session
        let api = self.api
        let check = await ApprovalCheck.run(reason: BipeStrings.appVerifyBiometric, pinTitle: BipeStrings.appVerifyIncomingTitle,
                                            prompt: pinPrompt) { (pin: String) async throws(BipeAPIError) in
            try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.checkPIN(pin, accessToken: token)
            }
        }
        switch check {
        case .passed:
            break
        case .failed:
            phase = .incoming
            return
        case .noScreenLock:
            phase = .failed
            try? await controller.cancelVerification()
            return
        }
        do {
            try await controller.acceptVerificationRequest()
            phase = .waitingForOther
        } catch {
            phase = .failed
        }
    }

    /// Action decline: approver says no to the request.
    func decline() async {
        try? await controller?.declineVerification()
        phase = .cancelled
    }

    /// Action match: the symbols are the same on both devices.
    func match() async {
        guard phase == .compare, let controller else { return }
        phase = .waitingForMatch
        do {
            try await controller.approveVerification()
        } catch {
            phase = .failed
        }
    }

    /// Action noMatch: the symbols differ. Nothing is approved.
    func noMatch() async {
        guard phase == .compare else { return }
        mismatch = true
        phase = .cancelled
        try? await controller?.declineVerification()
    }

    /// Action cancel: at any time before done.
    func cancel() async {
        guard !isFinished else { return }
        phase = .cancelled
        try? await controller?.cancelVerification()
    }

    /// Holds a background task while the flow runs and ends it on done, cancelled and failed.
    private func holdBackgroundTime() {
        if isFinished {
            endBackgroundTime()
        } else if backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "device-verification") { [weak self] in
                MainActor.assumeIsolated { self?.endBackgroundTime() }
            }
        }
    }

    private func endBackgroundTime() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// The controller, after start got it. A tap on the banner can come before that.
    private func readyController() async -> SessionVerificationController? {
        for _ in 0..<30 where controller == nil {
            try? await Task.sleep(for: .seconds(1))
        }
        return controller
    }

    private func handle(_ event: SessionVerificationEvent) async {
        switch event {
        case .receivedRequest(let details):
            // Action receive: only requests from this account's own devices (spec notes).
            guard details.senderProfile.userId == session.userId, isFinished || phase == .incoming else { return }
            do {
                try await controller?.acknowledgeVerificationRequest(senderId: details.senderProfile.userId, flowId: details.flowId)
            } catch {
                return
            }
            role = .approver
            otherDevice = RequestingDevice(deviceId: details.deviceId,
                                           displayName: details.deviceDisplayName,
                                           firstSeenAt: Date(milliseconds: details.firstSeenTimestamp))
            symbols = []
            mismatch = false
            phase = .incoming
            startCooldown()
            lookUpPlace(deviceId: details.deviceId)
            onIncoming?()
        case .accepted:
            // The other device accepted the request of this new phone: start SAS.
            guard role == .newPhone, phase == .waitingForOther else { return }
            do {
                try await controller?.startSasVerification()
            } catch {
                phase = .failed
            }
        case .startedSas:
            break
        case .receivedData(let data):
            // After match the flow waits for the other device; data that arrives again does not undo the match.
            guard !isFinished, phase != .waitingForMatch else { return }
            symbols = Self.symbols(of: data)
            phase = .compare
        case .finished:
            phase = .done
        case .cancelled:
            if phase != .done {
                phase = .cancelled
            }
        case .failed:
            if phase != .done {
                phase = .failed
            }
        }
    }

    /// Rule 5: the approve button waits webLinkCooldownSeconds, as on the QR approval.
    private func startCooldown() {
        countdown?.cancel()
        secondsLeft = BipeRegistries.webLinkCooldownSeconds
        countdown = Task { [weak self] in
            while let self, self.secondsLeft > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                self.secondsLeft -= 1
            }
        }
    }

    /// Rule 5: the approximate place of the requesting device. Its last IP comes from GET /_matrix/client/v3/devices
    /// (last_seen_ip); GET /v1/web-link/place turns it into a place, as on the QR approval. The IP goes to no outside
    /// service.
    private func lookUpPlace(deviceId: String) {
        placeLookUp?.cancel()
        place = nil
        placeKnown = false
        let session = self.session
        let api = self.api
        placeLookUp = Task { [weak self] in
            var found: String?
            if let homeserver = URL(string: session.client.homeserver()), let token = session.accessToken(),
               let ip = try? await BipeMatrixAccountAPI(homeserver: homeserver).devices(accessToken: token)
                   .first(where: { $0.deviceId == deviceId })?.lastSeenIp {
                found = try? await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> BipeWebLinkPlace in
                    try await api.webLinkPlace(ip: ip, accessToken: token)
                }.label()
            }
            guard !Task.isCancelled, let self, self.otherDevice?.deviceId == deviceId else { return }
            self.place = found
            self.placeKnown = true
        }
    }

    private static func symbols(of data: SessionVerificationData) -> [VerificationSymbol] {
        switch data {
        case .emojis(let emojis, _):
            emojis.enumerated().map { VerificationSymbol(id: $0.offset, symbol: $0.element.symbol(), label: $0.element.description()) }
        case .decimals(let values):
            values.enumerated().map { VerificationSymbol(id: $0.offset, symbol: String($0.element), label: "") }
        }
    }
}
