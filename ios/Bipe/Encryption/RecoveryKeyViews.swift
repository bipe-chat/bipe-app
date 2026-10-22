// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Spec recovery-key-save (bipe-shared product/screens/recovery-key-save.json).
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

    @Published private(set) var phase: Phase = .intro
    @Published private(set) var progress: Progress = .starting
    /// Held in memory while showKey lasts. Never logged, never stored by the app.
    @Published private(set) var recoveryKey: String?
    @Published private(set) var copied = false

    private let encryption: Encryption

    init(encryption: Encryption) {
        self.encryption = encryption
    }

    var recoveryState: RecoveryState { encryption.recoveryState() }

    /// Action create: a new key, or after the owner check a replacement.
    func create() async {
        let state = encryption.recoveryState()
        if state == .enabled || state == .incomplete {
            guard await OwnerCheck.confirm(reason: BipeStrings.appRecoveryKeyBiometric) else { return }
        }
        phase = .creating
        do {
            let key: String
            if state == .disabled || state == .unknown {
                let stream = StateStream<EnableRecoveryProgress>()
                Task { [weak self] in
                    for await status in stream.updates { self?.update(status) }
                }
                key = try await encryption.enableRecovery(waitForBackupsToUpload: true, passphrase: nil,
                                                          progressListener: ProgressListener(stream))
                stream.continuation.finish()
            } else {
                key = try await encryption.resetRecoveryKey()
            }
            recoveryKey = key
            phase = .showKey
        } catch {
            phase = .failed
        }
    }

    private func update(_ status: EnableRecoveryProgress) {
        progress = switch status {
        case .starting: .starting
        case .creatingBackup: .creatingBackup
        case .creatingRecoveryKey: .creatingRecoveryKey
        case .backingUp: .backingUp
        case .roomKeyUploadError: .roomKeyUploadError
        case .done: .done
        }
    }

    /// Action copy: marked local-only so it does not reach other devices, cleared after 60 s.
    func copy() {
        guard let recoveryKey else { return }
        UIPasteboard.general.setItems([[UTType.utf8PlainText.identifier: recoveryKey]],
                                      options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(60)])
        copied = true
    }

    /// Action confirmSaved.
    func confirmSaved() {
        recoveryKey = nil
        phase = .saved
    }

    /// The key in groups of 4 characters.
    var groupedKey: String {
        guard let recoveryKey else { return "" }
        let compact = recoveryKey.filter { !$0.isWhitespace }
        return stride(from: 0, to: compact.count, by: 4).map { start in
            let begin = compact.index(compact.startIndex, offsetBy: start)
            let end = compact.index(begin, offsetBy: min(4, compact.count - start))
            return String(compact[begin..<end])
        }.joined(separator: " ")
    }
}

final class ProgressListener: EnableRecoveryProgressListener {
    private let stream: StateStream<EnableRecoveryProgress>
    init(_ stream: StateStream<EnableRecoveryProgress>) { self.stream = stream }
    func onUpdate(status: EnableRecoveryProgress) { stream.continuation.yield(status) }
}

struct RecoveryKeySaveView: View {
    @StateObject private var model: RecoveryKeySaveModel
    @Environment(\.dismiss) private var dismiss

    init(encryption: Encryption) {
        _model = StateObject(wrappedValue: RecoveryKeySaveModel(encryption: encryption))
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.accountRecoveryKey)
                    .font(.largeTitle.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                switch model.phase {
                case .intro, .failed:
                    let replacing = model.recoveryState == .enabled || model.recoveryState == .incomplete
                    Text(replacing ? BipeStrings.appRecoveryKeyReplaceBody : BipeStrings.fallbackRecoveryKeyBody)
                        .foregroundStyle(BipeColor.inkSecondary)
                    if model.phase == .failed {
                        FieldFooter(text: BipeStrings.errorGeneric, isError: true)
                    }
                    Spacer()
                    PrimaryButton(title: replacing ? BipeStrings.appRecoveryKeyReplace : BipeStrings.appRecoveryKeyCreate) {
                        Task { await model.create() }
                    }
                case .creating:
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(BipeStrings.appRecoveryKeyCreating).foregroundStyle(BipeColor.inkSecondary)
                    }
                    if model.progress == .roomKeyUploadError {
                        FieldFooter(text: BipeStrings.errorGeneric, isError: true)
                    }
                    Spacer()
                case .showKey:
                    Text(BipeStrings.appRecoveryKeyShowBody)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(model.groupedKey)
                        .font(.title3.monospaced())
                        .foregroundStyle(BipeColor.inkPrimary)
                        .textSelection(.enabled)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                    HStack {
                        Button { model.copy() } label: {
                            Text(model.copied ? BipeStrings.appCopied : BipeStrings.appCopy)
                        }
                        Spacer()
                        ShareLink(item: model.groupedKey) { Text(BipeStrings.appShare) }
                    }
                    .foregroundStyle(BipeColor.accentInk)
                    Spacer()
                    PrimaryButton(title: BipeStrings.appRecoveryKeySavedAction) { model.confirmSaved() }
                case .saved:
                    Text(BipeStrings.accountRecoveryKeySaved)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Spacer()
                    PrimaryButton(title: BipeStrings.actionContinue) { dismiss() }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(BipeStrings.appClose) }
                }
            }
        }
        .interactiveDismissDisabled(model.phase == .showKey || model.phase == .creating)
        .accessibilityIdentifier(BipeScreen.recoveryKeySave.rawValue)
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
    private let encryption: Encryption

    init(encryption: Encryption) {
        self.encryption = encryption
    }

    /// Action editKey.
    func editKey(_ text: String) {
        key = text
        if phase == .wrongKey || phase == .failed {
            phase = .editing
        }
    }

    /// Action submit. Spaces and line breaks are ignored.
    func submit() async {
        let compact = key.filter { !$0.isWhitespace }
        guard !compact.isEmpty else { return }
        phase = .checking
        do {
            try await encryption.recover(recoveryKey: compact)
            key = ""
            phase = .done
        } catch {
            phase = .wrongKey
        }
    }
}

struct RecoveryKeyEnterView: View {
    @StateObject private var model: RecoveryKeyEnterModel
    let noKey: () -> Void
    @Environment(\.dismiss) private var dismiss

    init(encryption: Encryption, noKey: @escaping () -> Void) {
        _model = StateObject(wrappedValue: RecoveryKeyEnterModel(encryption: encryption))
        self.noKey = noKey
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(BipeStrings.appRecoveryKeyEnterTitle)
                    .font(.title.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.appRecoveryKeyEnterBody)
                    .foregroundStyle(BipeColor.inkSecondary)
                if model.phase == .done {
                    Text(BipeStrings.appRecoveryKeyEnterDone).foregroundStyle(BipeColor.statusSuccess)
                    Spacer()
                    PrimaryButton(title: BipeStrings.actionContinue) { dismiss() }
                } else {
                    TextField(BipeStrings.accountRecoveryKey, text: Binding(get: { model.key }, set: model.editKey), axis: .vertical)
                        .font(.body.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(2...4)
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                    if model.phase == .checking {
                        FieldFooter(text: BipeStrings.appRecoveryKeyChecking)
                    } else if model.phase == .wrongKey {
                        FieldFooter(text: BipeStrings.appRecoveryKeyWrong, isError: true)
                    }
                    Spacer()
                    PrimaryButton(title: BipeStrings.actionContinue,
                                  enabled: !model.key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                                  busy: model.phase == .checking) {
                        Task { await model.submit() }
                    }
                    TextButton(title: BipeStrings.appRecoveryKeyNone) {
                        dismiss()
                        noKey()
                    }
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(BipeStrings.appClose) }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.recoveryKeyEnter.rawValue)
    }
}
