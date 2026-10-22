// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Spec recovery-key-save (bipe-shared product/screens/recovery-key-save.json): makes the recovery key for the message
/// history and shows it once (ADR 0073). Bipe never holds the key and never asks for it.
@MainActor
final class RecoveryKeySaveModel: ObservableObject {
    enum Phase: Equatable {
        case intro
        case creating
        case showKey
        case saved
        case failed
    }

    enum Progress: Equatable {
        case starting
        case creatingBackup
        case creatingRecoveryKey
        case backingUp
        case roomKeyUploadError
        case done
    }

    @Published private(set) var recoveryState: RecoveryState
    @Published private(set) var phase: Phase = .intro
    @Published private(set) var progress: Progress = .starting
    /// Held in memory while showKey lasts. Never logged, never stored by the app.
    @Published private(set) var recoveryKey: String?

    private let session: AccountSession
    private var handle: TaskHandle?

    init(session: AccountSession) {
        self.session = session
        recoveryState = session.client.encryption().recoveryState()
    }

    deinit {
        handle?.cancel()
    }

    /// Follows Encryption.recoveryStateListener while the screen is open.
    func observe() async {
        let stream = RecoveryStateStream()
        handle = session.client.encryption().recoveryStateListener(listener: stream)
        for await state in stream.updates {
            recoveryState = state
        }
    }

    var keyExists: Bool {
        recoveryState == .enabled || recoveryState == .incomplete
    }

    /// Action create: a first key with enableRecovery; a new key (the old one stops working) after the owner check.
    func create() async {
        let encryption = session.client.encryption()
        if keyExists {
            guard await OwnerCheck.owner(reason: BipeStrings.appRecoveryKeyBiometric) else { return }
        }
        phase = .creating
        progress = .starting
        do {
            let key: String
            if keyExists {
                key = try await encryption.resetRecoveryKey()
            } else {
                let listener = ProgressStream()
                Task { [weak self] in
                    for await update in listener.updates {
                        self?.progress = update
                    }
                }
                key = try await encryption.enableRecovery(waitForBackupsToUpload: true, passphrase: nil, progressListener: listener)
            }
            recoveryKey = key
            phase = .showKey
        } catch {
            phase = .failed
        }
    }

    /// The key in groups of 4 characters (spec notes).
    var groupedKey: String {
        let plain = (recoveryKey ?? "").filter { !$0.isWhitespace }
        return stride(from: 0, to: plain.count, by: 4).map { start -> String in
            let from = plain.index(plain.startIndex, offsetBy: start)
            let to = plain.index(from, offsetBy: 4, limitedBy: plain.endIndex) ?? plain.endIndex
            return String(plain[from..<to])
        }.joined(separator: " ")
    }

    /// Action copy: local only (no Universal Clipboard), and gone after 60 s.
    func copy() {
        guard let recoveryKey else { return }
        UIPasteboard.general.setItems([[UTType.plainText.identifier: recoveryKey]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
    }

    /// Action confirmSaved: clears the key from memory.
    func confirmSaved() {
        recoveryKey = nil
        phase = .saved
    }
}

/// EnableRecoveryProgressListener calls come on SDK threads; the stream hands them over in order.
private final class ProgressStream: EnableRecoveryProgressListener {
    let updates: AsyncStream<RecoveryKeySaveModel.Progress>
    private let continuation: AsyncStream<RecoveryKeySaveModel.Progress>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(status: EnableRecoveryProgress) {
        switch status {
        case .starting: continuation.yield(.starting)
        case .creatingBackup: continuation.yield(.creatingBackup)
        case .creatingRecoveryKey: continuation.yield(.creatingRecoveryKey)
        case .backingUp: continuation.yield(.backingUp)
        case .roomKeyUploadError: continuation.yield(.roomKeyUploadError)
        case .done: continuation.yield(.done)
        }
    }
}

struct RecoveryKeySaveView: View {
    @StateObject private var model: RecoveryKeySaveModel
    @State private var copied = false
    private let close: () -> Void

    init(session: AccountSession, close: @escaping () -> Void) {
        _model = StateObject(wrappedValue: RecoveryKeySaveModel(session: session))
        self.close = close
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.accountRecoveryKey)
                .font(.largeTitle.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            content
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .tint(BipeColor.accentFill)
        .interactiveDismissDisabled(model.phase == .creating || model.phase == .showKey)
        .accessibilityIdentifier(BipeScreen.recoveryKeySave.rawValue)
        .task { await model.observe() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .intro:
            Text(model.keyExists ? BipeStrings.accountRecoveryKeySaved : BipeStrings.accountRecoveryKeyMissing)
                .font(.headline)
                .foregroundStyle(model.keyExists ? BipeColor.trustVerifiedInk : BipeColor.inkSecondary)
            Text(model.keyExists ? BipeStrings.appRecoveryKeyReplaceBody : BipeStrings.fallbackRecoveryKeyBody)
                .foregroundStyle(BipeColor.inkSecondary)
            Text(BipeStrings.accountRecoveryKeyFooter)
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            Spacer()
            filled(model.keyExists ? BipeStrings.appRecoveryKeyReplace : BipeStrings.appRecoveryKeyCreate) {
                Task { await model.create() }
            }
        case .creating:
            Spacer()
            HStack(spacing: 8) {
                ProgressView()
                Text(BipeStrings.appRecoveryKeyCreating).foregroundStyle(BipeColor.inkSecondary)
            }
            .frame(maxWidth: .infinity)
            Spacer()
        case .showKey:
            Text(BipeStrings.appRecoveryKeyShowBody)
                .foregroundStyle(BipeColor.inkSecondary)
            Text(model.groupedKey)
                .font(.title3.monospaced())
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
            HStack(spacing: 12) {
                Button {
                    model.copy()
                    copied = true
                } label: {
                    Label {
                        Text(copied ? BipeStrings.appCopied : BipeStrings.appCopy)
                    } icon: {
                        PhosphorIcon.copy.image.resizable().frame(width: 18, height: 18)
                    }
                }
                .buttonStyle(.bordered)
                if let key = model.recoveryKey {
                    ShareLink(item: key) {
                        Text(BipeStrings.appShare)
                    }
                    .buttonStyle(.bordered)
                }
            }
            Spacer()
            filled(BipeStrings.appRecoveryKeySavedAction) { model.confirmSaved() }
        case .saved:
            Spacer()
            HStack(spacing: 8) {
                PhosphorIcon.checkCircle.image
                    .resizable()
                    .frame(width: 24, height: 24)
                    .foregroundStyle(BipeColor.statusSuccess)
                    .accessibilityHidden(true)
                Text(BipeStrings.accountRecoveryKeySaved).foregroundStyle(BipeColor.inkPrimary)
            }
            Spacer()
            filled(BipeStrings.actionContinue, action: close)
        case .failed:
            Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.trustDangerInk)
            Spacer()
            filled(BipeStrings.appRetry) { Task { await model.create() } }
        }
    }

    private func filled(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }
}
