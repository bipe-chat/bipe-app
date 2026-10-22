// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Spec encryption-status (bipe-shared product/screens/encryption-status.json): follows the SDK verification,
/// recovery and backup state, and shows the history banner and sheet when this phone cannot read old messages.
@MainActor
final class EncryptionModel: ObservableObject {
    @Published private(set) var verificationState: VerificationState = .unknown
    @Published private(set) var recoveryState: RecoveryState = .unknown
    @Published private(set) var backupState: BackupState = .unknown
    @Published private(set) var hasOtherDevices = false
    @Published private(set) var bannerDismissed: Bool
    @Published var sheetOpen = false
    /// Sheets the encryption-status rows open.
    @Published var showsKeyEnter = false
    @Published var showsVerification = false
    @Published var showsKeySave = false

    let session: AccountSession
    private var handles: [TaskHandle] = []
    private var dismissedKey: String { "bipe.history.dismissedAt.\(session.userId)" }

    /// The banner comes back after 7 days while unverified.
    private static let bannerPause: TimeInterval = 7 * 24 * 3600

    init(session: AccountSession) {
        self.session = session
        let dismissedAt = UserDefaults.standard.object(forKey: "bipe.history.dismissedAt.\(session.userId)") as? Date
        bannerDismissed = dismissedAt.map { Date().timeIntervalSince($0) < Self.bannerPause } ?? false
    }

    deinit {
        handles.forEach { $0.cancel() }
    }

    var encryption: Encryption { session.client.encryption() }

    /// Action observe.
    func observe() async {
        guard handles.isEmpty else { return }
        let encryption = encryption
        verificationState = encryption.verificationState()
        recoveryState = encryption.recoveryState()
        backupState = encryption.backupState()
        let verification = StateStream<VerificationState>()
        let recovery = StateStream<RecoveryState>()
        let backup = StateStream<BackupState>()
        handles = [
            encryption.verificationStateListener(listener: VerificationListener(verification)),
            encryption.recoveryStateListener(listener: RecoveryListener(recovery)),
            encryption.backupStateListener(listener: BackupListener(backup)),
        ]
        hasOtherDevices = (try? await encryption.hasDevicesToVerifyAgainst()) ?? false
        Task { [weak self] in for await state in verification.updates { self?.verificationState = state } }
        Task { [weak self] in for await state in recovery.updates { self?.recoveryState = state } }
        Task { [weak self] in for await state in backup.updates { self?.backupState = state } }
    }

    /// The banner shows only when this phone is unverified and the account has history elsewhere.
    var showsBanner: Bool {
        verificationState == .unverified && hasHistoryElsewhere && !bannerDismissed
    }

    var hasHistoryElsewhere: Bool {
        hasOtherDevices || recoveryState == .enabled || recoveryState == .incomplete
    }

    var canUseRecoveryKey: Bool {
        recoveryState == .enabled || recoveryState == .incomplete
    }

    /// Action openSheet.
    func openSheet() {
        sheetOpen = true
    }

    /// Action approveWithDevice.
    func approveWithDevice() {
        sheetOpen = false
        showsVerification = true
    }

    /// Action useRecoveryKey.
    func useRecoveryKey() {
        sheetOpen = false
        showsKeyEnter = true
    }

    /// Action dismiss.
    func dismiss() {
        bannerDismissed = true
        UserDefaults.standard.set(Date(), forKey: dismissedKey)
    }

    /// recovery-key-save prompt above the chat list: an account with no recovery key, until "Agora não".
    var showsKeyPrompt: Bool {
        recoveryState == .disabled && verificationState == .verified
            && !UserDefaults.standard.bool(forKey: "bipe.recoveryKeyPrompt.dismissed.\(session.userId)")
    }

    func dismissKeyPrompt() {
        UserDefaults.standard.set(true, forKey: "bipe.recoveryKeyPrompt.dismissed.\(session.userId)")
        objectWillChange.send()
    }
}

/// SDK state listeners feed AsyncStreams, read in order on the main actor.
final class StateStream<State> {
    let updates: AsyncStream<State>
    let continuation: AsyncStream<State>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }
}

final class VerificationListener: VerificationStateListener {
    private let stream: StateStream<VerificationState>
    init(_ stream: StateStream<VerificationState>) { self.stream = stream }
    func onUpdate(status: VerificationState) { stream.continuation.yield(status) }
}

final class RecoveryListener: RecoveryStateListener {
    private let stream: StateStream<RecoveryState>
    init(_ stream: StateStream<RecoveryState>) { self.stream = stream }
    func onUpdate(status: RecoveryState) { stream.continuation.yield(status) }
}

final class BackupListener: BackupStateListener {
    private let stream: StateStream<BackupState>
    init(_ stream: StateStream<BackupState>) { self.stream = stream }
    func onUpdate(status: BackupState) { stream.continuation.yield(status) }
}
