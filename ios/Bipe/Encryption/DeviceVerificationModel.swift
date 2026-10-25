// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import LocalAuthentication
import MatrixRustSDK

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
    @Published private(set) var phase: Phase = .cancelled
    @Published private(set) var otherDevice: RequestingDevice?
    @Published private(set) var symbols: [VerificationSymbol] = []
    /// The flow ended with noMatch: the screen shows bipe_app_verify_mismatch.
    @Published private(set) var mismatch = false
    /// Action notifyAgain is open: newPhone waits and the last nudge is approvalNudgeAgainSeconds old.
    @Published private(set) var canNotifyAgain = false

    /// Called when a request from another device of this account arrives (action receive).
    var onIncoming: (@MainActor () -> Void)?

    private let session: AccountSession
    private let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    private var nudgeWait: Task<Void, Never>?
    private let delegate = SessionVerificationStream()
    private var controller: SessionVerificationController?
    private var started = false

    init(session: AccountSession) {
        self.session = session
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

    /// Action accept: approver passes the device owner check, then accepts.
    func accept() async {
        guard role == .approver, phase == .incoming, let controller else { return }
        phase = .confirmIdentity
        guard await DeviceOwnerCheck.confirm() else {
            phase = .incoming
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
            guard !isFinished else { return }
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

    private static func symbols(of data: SessionVerificationData) -> [VerificationSymbol] {
        switch data {
        case .emojis(let emojis, _):
            emojis.enumerated().map { VerificationSymbol(id: $0.offset, symbol: $0.element.symbol(), label: $0.element.description()) }
        case .decimals(let values):
            values.enumerated().map { VerificationSymbol(id: $0.offset, symbol: String($0.element), label: "") }
        }
    }
}

/// The biometric check (or the device passcode) before an approval (ADR 0059 QR approval rule).
enum DeviceOwnerCheck {
    static func confirm() async -> Bool {
        let context = LAContext()
        var error: NSError?
        // A phone without a passcode has nothing to check.
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return true }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: BipeStrings.appVerifyBiometric)) ?? false
    }
}
