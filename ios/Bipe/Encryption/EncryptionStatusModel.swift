// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Spec encryption-status (bipe-shared product/screens/encryption-status.json): the one place that
/// explains missing history (ADR 0073). It also opens device-verification and recovery-key-enter.
@MainActor
final class EncryptionStatusModel: ObservableObject {
    /// The sheet that is open. history is the sheet of spec encryption-status (state sheetOpen).
    enum Route: String, Identifiable {
        case history
        case verification
        case recoveryKey

        var id: String { rawValue }
    }

    /// The banner comes back after this time while the phone is unverified (spec state bannerDismissed).
    static let bannerPause: TimeInterval = 7 * 24 * 60 * 60

    @Published private(set) var verificationState: VerificationState = .unknown
    @Published private(set) var recoveryState: RecoveryState = .unknown
    @Published private(set) var backupState: BackupState = .unknown
    @Published private(set) var hasOtherDevices = false
    @Published private(set) var bannerDismissed = false
    @Published var route: Route?

    let verification: DeviceVerificationModel
    private let session: AccountSession
    private var handles: [TaskHandle] = []
    private var started = false

    init(session: AccountSession) {
        self.session = session
        verification = DeviceVerificationModel(session: session)
        bannerDismissed = Self.isBannerPaused(userId: session.userId)
        verification.onIncoming = { [weak self] in
            self?.route = .verification
        }
    }

    deinit {
        handles.forEach { $0.cancel() }
    }

    /// The recovery key row shows (state recoveryState enabled or incomplete).
    var canUseRecoveryKey: Bool {
        recoveryState == .enabled || recoveryState == .incomplete
    }

    /// Spec notes: this phone is unverified and the account has history elsewhere. The settings row oldMessages
    /// shows while this is true, also after Skip on the banner (bipe-chat/bipe#793).
    var needsApproval: Bool {
        verificationState == .unverified && (hasOtherDevices || canUseRecoveryKey)
    }

    /// The banner: needsApproval, unless the person tapped Skip less than bannerPause ago.
    var showsBanner: Bool {
        needsApproval && !bannerDismissed
    }

    /// Action observe: listens to the SDK verification, recovery and backup state.
    func observe() async {
        guard !started else { return }
        started = true
        let encryption = session.client.encryption()
        let verificationStream = VerificationStateStream()
        let recoveryStream = RecoveryStateStream()
        let backupStream = BackupStateStream()
        handles.append(encryption.verificationStateListener(listener: verificationStream))
        handles.append(encryption.recoveryStateListener(listener: recoveryStream))
        handles.append(encryption.backupStateListener(listener: backupStream))
        verificationState = encryption.verificationState()
        recoveryState = encryption.recoveryState()
        backupState = encryption.backupState()

        Task { [weak self] in
            for await state in recoveryStream.updates {
                self?.recoveryState = state
            }
        }
        Task { [weak self] in
            for await state in backupStream.updates {
                self?.backupState = state
            }
        }
        Task { [weak self] in
            await self?.verification.start()
        }
        await refreshOtherDevices()
        for await state in verificationStream.updates {
            verificationState = state
            await refreshOtherDevices()
        }
    }

    /// Action openSheet.
    func openSheet() {
        route = .history
        Task { await refreshOtherDevices() }
    }

    /// Action approveWithDevice: device-verification as newPhone.
    func approveWithDevice() {
        guard hasOtherDevices else { return }
        route = .verification
        Task { await verification.request() }
    }

    /// Action useRecoveryKey: recovery-key-enter.
    func useRecoveryKey() {
        guard canUseRecoveryKey else { return }
        route = .recoveryKey
    }

    /// Action dismiss: hides the banner on this phone for bannerPause.
    func dismiss() {
        bannerDismissed = true
        UserDefaults.standard.set(Date(), forKey: Self.bannerKey(userId: session.userId))
    }

    /// A sheet closed. A verification that is still open ends, unless its sheet is the one that replaced it.
    func sheetClosed() {
        if route != .verification && !verification.isFinished {
            Task { await verification.cancel() }
        }
    }

    private func refreshOtherDevices() async {
        if let value = try? await session.client.encryption().hasDevicesToVerifyAgainst() {
            hasOtherDevices = value
        }
    }

    private static func bannerKey(userId: String) -> String {
        "bipe.encryptionStatus.bannerDismissedAt.\(userId)"
    }

    private static func isBannerPaused(userId: String) -> Bool {
        guard let date = UserDefaults.standard.object(forKey: bannerKey(userId: userId)) as? Date else { return false }
        return Date().timeIntervalSince(date) < bannerPause
    }
}

/// Spec recovery-key-enter (bipe-shared product/screens/recovery-key-enter.json).
@MainActor
final class RecoveryKeyEnterModel: ObservableObject {
    enum Phase: Equatable {
        case editing
        case checking
        case wrongKey
        case done
        case failed
    }

    @Published private(set) var key = ""
    @Published private(set) var phase: Phase = .editing

    private let session: AccountSession

    init(session: AccountSession) {
        self.session = session
    }

    /// Off while the key is blank or a check runs.
    var canSubmit: Bool {
        !key.allSatisfy(\.isWhitespace) && phase != .checking && phase != .done
    }

    /// Action editKey.
    func editKey(_ text: String) {
        key = text
        if phase == .wrongKey || phase == .failed {
            phase = .editing
        }
    }

    /// Action submit: Encryption.recover. The key is never logged.
    func submit() async {
        guard canSubmit else { return }
        phase = .checking
        let recoveryKey = key.filter { !$0.isWhitespace }
        do {
            try await session.client.encryption().recover(recoveryKey: recoveryKey)
            phase = .done
        } catch RecoveryError.SecretStorage, RecoveryError.Import {
            phase = .wrongKey
        } catch {
            phase = .failed
        }
    }
}
