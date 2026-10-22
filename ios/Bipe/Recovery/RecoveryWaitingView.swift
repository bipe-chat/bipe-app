// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec recovery-waiting (bipe-shared product/screens/recovery-waiting.json).
@MainActor
final class RecoveryWaitingModel: ObservableObject {
    enum Phase: Equatable {
        case waiting
        case claiming
        case ready
        case cancelled
        case expired
        case failed
    }

    @Published private(set) var phase: Phase = .waiting
    @Published private(set) var purpose: BipeRecoveryPurpose = .access
    @Published private(set) var readyAt = Date()

    private let api: BipeAPIClient
    private let store = BipeCredentialStore()
    private let onSignedIn: (AccountSession) -> Void
    private let onStartOver: () -> Void
    private var timer: Task<Void, Never>?

    init(api: BipeAPIClient, onSignedIn: @escaping (AccountSession) -> Void, onStartOver: @escaping () -> Void) {
        self.api = api
        self.onSignedIn = onSignedIn
        self.onStartOver = onStartOver
        if let kept = store.pendingRecovery() {
            purpose = kept.purpose
            readyAt = kept.readyAt
        }
    }

    deinit {
        timer?.cancel()
    }

    /// Action appear: polls the claim on open, on foreground and at readyAt.
    func appear() async {
        await claim()
        timer?.cancel()
        let wait = readyAt.timeIntervalSinceNow
        guard phase == .waiting, wait > 0 else { return }
        timer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(wait + 1))
            guard !Task.isCancelled else { return }
            await self?.claim()
        }
    }

    /// Action claim.
    func claim() async {
        guard let kept = store.pendingRecovery() else { return }
        let before = phase
        if readyAt <= Date() {
            phase = .claiming
        }
        do {
            let result = try await api.claimRecovery(id: kept.recoveryId, claimSecret: kept.claimSecret)
            switch result.state {
            case .waiting:
                phase = .waiting
                if let ready = result.readyAt { readyAt = ready }
            case .completed:
                if let credentials = result.credentials {
                    _ = store.save(credentials)
                    store.deletePendingRecovery()
                    let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
                    phase = .ready
                    onSignedIn(session)
                } else {
                    // pinReset: the new PIN is in force.
                    store.deletePendingRecovery()
                    phase = .ready
                }
            case .cancelled:
                phase = .cancelled
            case .expired:
                phase = .expired
            }
        } catch let error as BipeAPIError {
            if case .server(.recoveryInvalid, _, _) = error {
                phase = .expired
            } else {
                phase = before == .claiming ? .failed : before
            }
        } catch {
            phase = .failed
        }
    }

    /// Action startOver: from cancelled or expired.
    func startOver() {
        store.deletePendingRecovery()
        onStartOver()
    }
}

struct RecoveryWaitingView: View {
    @StateObject private var model: RecoveryWaitingModel
    @Environment(\.scenePhase) private var scenePhase

    init(api: BipeAPIClient, onSignedIn: @escaping (AccountSession) -> Void, onStartOver: @escaping () -> Void) {
        _model = StateObject(wrappedValue: RecoveryWaitingModel(api: api, onSignedIn: onSignedIn, onStartOver: onStartOver))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            PhosphorIcon.lockSimple.image
                .resizable()
                .frame(width: 48, height: 48)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(BipeStrings.recoveryWaitingTitle)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(message)
                .foregroundStyle(BipeColor.inkPrimary)
            if model.phase == .ready, model.purpose == .access {
                Text(BipeStrings.recoveryHistoryNote)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if model.phase == .claiming {
                ProgressView()
            }
            Spacer()
            if model.phase == .cancelled || model.phase == .expired {
                PrimaryButton(title: BipeStrings.appRetry) { model.startOver() }
            } else if model.phase == .failed {
                PrimaryButton(title: BipeStrings.appRetry) { Task { await model.claim() } }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .accessibilityIdentifier(BipeScreen.recoveryWaiting.rawValue)
        .task { await model.appear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.appear() }
            }
        }
    }

    private var message: String {
        let date = model.readyAt.formatted(date: .abbreviated, time: .shortened)
        switch model.phase {
        case .ready: return model.purpose == .access ? BipeStrings.recoveryReady : BipeStrings.recoveryPinReady
        case .cancelled: return BipeStrings.recoveryCancelled
        case .expired: return BipeStrings.errorRecoveryInvalid
        case .failed: return BipeStrings.errorNetwork
        case .waiting, .claiming:
            return model.purpose == .access ? BipeStrings.recoveryWaitingBody(date) : BipeStrings.recoveryWaitingPinBody(date)
        }
    }
}
