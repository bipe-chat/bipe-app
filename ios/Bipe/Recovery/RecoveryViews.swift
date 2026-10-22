// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// "Já tenho conta": recovery-start and the steps it opens.
struct RecoveryFlowView: View {
    @ObservedObject var model: RecoveryFlowModel
    let close: () -> Void

    var body: some View {
        NavigationStack(path: $model.path) {
            RecoveryStartView(model: model)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(action: close) { Text(BipeStrings.appClose) }
                    }
                }
                .navigationDestination(for: RecoveryFlowModel.Route.self) { route in
                    Group {
                        switch route {
                        case .pin: RecoveryPinView(model: model)
                        case .email: RecoveryEmailView(model: model)
                        case .newPin: RecoveryNewPinView(model: model, confirm: false)
                        case .newPinConfirm: RecoveryNewPinView(model: model, confirm: true)
                        case .beta: RecoveryBetaView(model: model)
                        }
                    }
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                }
        }
        .tint(BipeColor.accentFill)
        .onOpenURL { model.openLink($0) }
    }
}

/// The digit field of the PIN steps: the system digit keyboard behind a row of dots.
struct PinField: View {
    let pin: String
    let onChange: (String) -> Void
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            TextField("", text: Binding(get: { pin }, set: onChange))
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($focused)
                .opacity(0.02)
                .accessibilityHidden(true)
            HStack(spacing: 10) {
                ForEach(0..<BipeRegistries.pinLength, id: \.self) { index in
                    Circle()
                        .fill(index < pin.count ? BipeColor.accentFill : BipeColor.surfaceSunken)
                        .frame(width: 14, height: 14)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
        }
        .onAppear { focused = true }
    }
}

// MARK: - recovery-start

struct RecoveryStartView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        StepLayout(screen: .recoveryStart, title: BipeStrings.recoveryTitle) {
            ForEach(model.rows, id: \.self) { row in
                Button { choose(row) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(BipeLocalized.string(row.titleKey))
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(rowBody(row))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.surfaceRaised))
                }
            }
        }
        .onAppear { model.appear() }
    }

    private func rowBody(_ row: BipeOnboardingRecoveryRow) -> String {
        row == .email
            ? BipeLocalized.string(row.bodyKey, String(BipeRegistries.recoveryDelayHoursEmail))
            : BipeLocalized.string(row.bodyKey)
    }

    private func choose(_ row: BipeOnboardingRecoveryRow) {
        switch row {
        case .token: model.chooseToken()
        case .email: model.chooseEmail()
        case .beta: model.chooseBeta()
        case .oldPhone: break
        }
    }
}

// MARK: - recovery-pin

struct RecoveryPinView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        StepLayout(screen: .recoveryPin,
                   title: model.pinPhase == .blocked ? BipeStrings.pinBlockedTitle : BipeStrings.pinEnterTitle,
                   message: model.factor == .token ? BipeStrings.recoveryTokenFound : nil) {
            if model.pinPhase == .blocked {
                Text(BipeStrings.pinBlockedBody(String(BipeRegistries.pinMaxWrongTries)))
                    .foregroundStyle(BipeColor.inkPrimary)
            } else {
                PinField(pin: model.pin) { text in Task { await model.editPin(text) } }
                if model.pinPhase == .checking {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if model.pinPhase == .wrong {
                    FieldFooter(text: BipeStrings.pinWrong(String(model.triesLeft)), isError: true)
                } else if let error = model.pinError {
                    FieldFooter(text: error, isError: true)
                }
                FieldFooter(text: BipeStrings.pinNeverAsked)
            }
            TextButton(title: BipeStrings.pinForgot) { model.forgotPin() }
        }
    }
}

struct RecoveryNewPinView: View {
    @ObservedObject var model: RecoveryFlowModel
    let confirm: Bool

    var body: some View {
        StepLayout(screen: .recoveryPin,
                   title: confirm ? BipeStrings.pinConfirmTitle : BipeStrings.recoveryNewPinTitle,
                   message: confirm ? nil : BipeStrings.pinCreateBody(String(BipeRegistries.pinLength))) {
            PinField(pin: model.newPin) { text in Task { await model.editNewPin(text, confirm: confirm) } }
            if let problem = model.newPinProblem {
                FieldFooter(text: problem, isError: true)
            }
            if model.pinPhase == .checking {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error = model.pinError {
                FieldFooter(text: error, isError: true)
            }
        }
    }
}

// MARK: - recovery-email

struct RecoveryEmailView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        StepLayout(screen: .recoveryEmail,
                   title: BipeStrings.recoveryEmail,
                   message: BipeStrings.recoveryEmailBody(String(BipeRegistries.recoveryDelayHoursEmail))) {
            if model.emailPhase == .sent {
                Text(BipeStrings.recoveryEmailSent)
                    .foregroundStyle(BipeColor.inkPrimary)
            } else {
                BipeTextField(label: BipeStrings.recoveryEmailField, text: model.email, onChange: model.editEmail,
                              keyboard: .emailAddress, contentType: .emailAddress)
                if model.emailInvalid {
                    FieldFooter(text: BipeStrings.signupEmailInvalid, isError: true)
                } else if let error = model.emailError {
                    FieldFooter(text: error, isError: true)
                }
                PrimaryButton(title: BipeStrings.appRecoveryEmailSend, busy: model.emailPhase == .sending) {
                    Task { await model.send() }
                }
            }
        }
    }
}

// MARK: - beta sign-in

struct RecoveryBetaView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        StepLayout(screen: .recoveryStart, title: BipeStrings.recoveryBeta, message: BipeStrings.recoveryBetaBody) {
            BipeTextField(label: BipeStrings.signupEmail, text: model.betaEmail, onChange: model.editBetaEmail,
                          keyboard: .emailAddress, contentType: .username)
            SecureField(BipeStrings.recoveryBetaPassword, text: Binding(get: { model.betaPassword }, set: model.editBetaPassword))
                .textContentType(.password)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
            if let error = model.betaError {
                FieldFooter(text: error, isError: true)
            }
            PrimaryButton(title: BipeStrings.actionContinue,
                          enabled: !model.betaEmail.isEmpty && !model.betaPassword.isEmpty,
                          busy: model.betaBusy) {
                Task { await model.signInBeta() }
            }
        }
    }
}
