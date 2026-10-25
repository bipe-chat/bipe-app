// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec recovery-key-enter: type or paste the recovery key to bring back the message history.
struct RecoveryKeyEnterView: View {
    @StateObject private var model: RecoveryKeyEnterModel
    @FocusState private var focused: Bool
    private let close: () -> Void
    private let noKey: () -> Void

    init(session: AccountSession, close: @escaping () -> Void, noKey: @escaping () -> Void) {
        _model = StateObject(wrappedValue: RecoveryKeyEnterModel(session: session))
        self.close = close
        self.noKey = noKey
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.appRecoveryKeyEnterTitle)
                .font(.title2.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.appRecoveryKeyEnterBody)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
            TextField("", text: Binding(get: { model.key }, set: { model.editKey($0) }), axis: .vertical)
                .font(.body.monospaced())
                .lineLimit(2...4)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .disabled(model.phase == .checking || model.phase == .done)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(model.phase == .wrongKey ? BipeColor.trustDangerLine : BipeColor.lineSubtle))
            status
            Spacer(minLength: 0)
            Button {
                focused = false
                Task { await model.submit() }
            } label: {
                Text(BipeStrings.actionContinue).frame(maxWidth: .infinity)
            }
            .buttonStyle(.bipeProminent)
            .controlSize(.large)
            .disabled(!model.canSubmit)
            Button(action: noKey) {
                Text(BipeStrings.appRecoveryKeyNone).frame(maxWidth: .infinity)
            }
            .controlSize(.large)
            .disabled(model.phase == .checking)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .presentationDetents([.large])
        .interactiveDismissDisabled(model.phase == .checking)
        .accessibilityIdentifier(BipeScreen.recoveryKeyEnter.rawValue)
        .onAppear { focused = true }
        // Action submit: on success the screen shows done and closes.
        .task(id: model.phase) {
            guard model.phase == .done else { return }
            try? await Task.sleep(for: .seconds(2))
            if !Task.isCancelled { close() }
        }
    }

    @ViewBuilder
    private var status: some View {
        switch model.phase {
        case .editing:
            EmptyView()
        case .checking:
            HStack(spacing: 8) {
                ProgressView()
                Text(BipeStrings.appRecoveryKeyChecking)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
        case .wrongKey:
            Text(BipeStrings.appRecoveryKeyWrong)
                .foregroundStyle(BipeColor.trustDangerInk)
        case .done:
            HStack(spacing: 8) {
                PhosphorIcon.checkCircle.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.statusSuccess)
                    .accessibilityHidden(true)
                Text(BipeStrings.appRecoveryKeyEnterDone)
                    .foregroundStyle(BipeColor.inkPrimary)
            }
        case .failed:
            Text(BipeStrings.errorNetwork)
                .foregroundStyle(BipeColor.trustDangerInk)
        }
    }
}
