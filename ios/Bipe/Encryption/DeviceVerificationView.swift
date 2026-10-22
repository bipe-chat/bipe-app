// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec device-verification (bipe-shared product/screens/device-verification.json): Matrix SAS between two devices
/// of the same account. One model per session listens for incoming requests (approver) and runs requests (newPhone).
@MainActor
final class DeviceVerificationModel: ObservableObject {
    enum Role: Equatable {
        case newPhone
        case approver
    }

    enum Phase: Equatable {
        case idle
        case requesting
        case waitingForOther
        case incoming
        case confirmIdentity
        case compare
        case waitingForMatch
        case done
        case cancelled
        case mismatch
        case failed
    }

    struct Symbol: Equatable, Identifiable {
        let id: Int
        let symbol: String
        let label: String
    }

    @Published private(set) var role: Role = .newPhone
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var otherDevice: SessionVerificationRequestDetails?
    @Published private(set) var symbols: [Symbol] = []

    private let session: AccountSession
    private var controller: SessionVerificationController?
    private var delegate: VerificationDelegate?

    init(session: AccountSession) {
        self.session = session
    }

    /// Shown over the app (approver) or as a sheet (newPhone) while a verification runs.
    var isActive: Bool {
        phase != .idle
    }

    /// Starts listening for requests from the account's other devices.
    func start() async {
        guard controller == nil, let controller = try? await session.client.getSessionVerificationController() else { return }
        self.controller = controller
        let stream = StateStream<VerificationEvent>()
        let delegate = VerificationDelegate(stream)
        self.delegate = delegate
        controller.setDelegate(delegate: delegate)
        for await event in stream.updates {
            handle(event)
        }
    }

    /// Action request (newPhone).
    func request() async {
        role = .newPhone
        phase = .requesting
        do {
            try await controller?.requestDeviceVerification()
            phase = .waitingForOther
        } catch {
            phase = .failed
        }
    }

    /// Action accept (approver): after the owner check.
    func accept() async {
        phase = .confirmIdentity
        guard await OwnerCheck.confirm(reason: BipeStrings.appVerifyBiometric) else {
            phase = .incoming
            return
        }
        do {
            try await controller?.acceptVerificationRequest()
            phase = .waitingForOther
        } catch {
            phase = .failed
        }
    }

    /// Action decline (approver).
    func decline() async {
        try? await controller?.declineVerification()
        reset()
    }

    /// Action match: the symbols are the same.
    func match() async {
        phase = .waitingForMatch
        do {
            try await controller?.approveVerification()
        } catch {
            phase = .failed
        }
    }

    /// Action noMatch: nothing is approved.
    func noMatch() async {
        try? await controller?.declineVerification()
        phase = .mismatch
    }

    /// Action cancel.
    func cancel() async {
        try? await controller?.cancelVerification()
        reset()
    }

    func reset() {
        phase = .idle
        symbols = []
        otherDevice = nil
    }

    private func handle(_ event: VerificationEvent) {
        switch event {
        case .request(let details):
            guard phase == .idle else { return }
            role = .approver
            otherDevice = details
            phase = .incoming
            Task { try? await controller?.acknowledgeVerificationRequest(senderId: details.senderProfile.userId, flowId: details.flowId) }
        case .accepted:
            // The new phone starts SAS when the accept arrives.
            if role == .newPhone {
                Task { try? await controller?.startSasVerification() }
            }
        case .sasStarted:
            break
        case .data(let data):
            if case .emojis(let emojis, _) = data {
                symbols = emojis.enumerated().map { Symbol(id: $0.offset, symbol: $0.element.symbol(), label: $0.element.description()) }
            } else if case .decimals(let values) = data {
                symbols = values.enumerated().map { Symbol(id: $0.offset, symbol: String($0.element), label: "") }
            }
            phase = .compare
        case .finished:
            phase = .done
        case .cancelled:
            if phase != .mismatch { phase = .cancelled }
        case .failed:
            phase = .failed
        }
    }
}

enum VerificationEvent {
    case request(SessionVerificationRequestDetails)
    case accepted
    case sasStarted
    case data(SessionVerificationData)
    case finished
    case cancelled
    case failed
}

final class VerificationDelegate: SessionVerificationControllerDelegate {
    private let stream: StateStream<VerificationEvent>
    init(_ stream: StateStream<VerificationEvent>) { self.stream = stream }
    func didReceiveVerificationRequest(details: SessionVerificationRequestDetails) { stream.continuation.yield(.request(details)) }
    func didAcceptVerificationRequest() { stream.continuation.yield(.accepted) }
    func didStartSasVerification() { stream.continuation.yield(.sasStarted) }
    func didReceiveVerificationData(data: SessionVerificationData) { stream.continuation.yield(.data(data)) }
    func didFail() { stream.continuation.yield(.failed) }
    func didCancel() { stream.continuation.yield(.cancelled) }
    func didFinish() { stream.continuation.yield(.finished) }
}

struct DeviceVerificationView: View {
    @ObservedObject var model: DeviceVerificationModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(model.role == .approver && model.phase == .incoming ? BipeStrings.appVerifyIncomingTitle : BipeStrings.appVerifyTitle)
                .font(.title.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            content
            Spacer()
            footer
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.deviceVerification.rawValue)
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .idle, .requesting, .waitingForOther, .confirmIdentity, .waitingForMatch:
            Text(model.role == .newPhone ? BipeStrings.appVerifyBody : BipeStrings.appVerifyWaiting)
                .foregroundStyle(BipeColor.inkSecondary)
            ProgressView()
        case .incoming:
            if let device = model.otherDevice {
                let name = device.deviceDisplayName ?? device.deviceId
                let time = Date(milliseconds: device.firstSeenTimestamp).formatted(date: .omitted, time: .shortened)
                Text(BipeStrings.appVerifyIncomingBody(name, time))
                    .foregroundStyle(BipeColor.inkPrimary)
            }
            Text(BipeStrings.deviceApprovalNeverForOthers)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
        case .compare:
            Text(BipeStrings.appVerifyCompare)
                .foregroundStyle(BipeColor.inkPrimary)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 12) {
                ForEach(model.symbols) { symbol in
                    VStack(spacing: 4) {
                        Text(symbol.symbol).font(.largeTitle)
                        Text(symbol.label).font(.caption2).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
        case .done:
            Text(BipeStrings.appVerifyDone).foregroundStyle(BipeColor.statusSuccess)
        case .cancelled:
            Text(BipeStrings.appVerifyCancelled).foregroundStyle(BipeColor.inkPrimary)
        case .mismatch:
            Text(BipeStrings.appVerifyMismatch).foregroundStyle(BipeColor.inkPrimary)
        case .failed:
            Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.trustDangerInk)
        }
    }

    @ViewBuilder
    private var footer: some View {
        switch model.phase {
        case .incoming:
            PrimaryButton(title: BipeStrings.appVerifyApprove) { Task { await model.accept() } }
            TextButton(title: BipeStrings.appCancel) { Task { await model.decline() } }
        case .compare:
            PrimaryButton(title: BipeStrings.appVerifyMatch) { Task { await model.match() } }
            TextButton(title: BipeStrings.appVerifyNoMatch) { Task { await model.noMatch() } }
        case .done, .cancelled, .mismatch, .failed:
            PrimaryButton(title: BipeStrings.actionContinue) { model.reset() }
        default:
            TextButton(title: BipeStrings.appCancel) { Task { await model.cancel() } }
        }
    }
}
