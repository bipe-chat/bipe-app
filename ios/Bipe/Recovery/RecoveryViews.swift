// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// The recovery flow: recovery-start and the screens it opens. account-start shows it.
struct RecoveryFlowView: View {
    @StateObject private var model: RecoveryFlowModel
    @Environment(\.scenePhase) private var scenePhase
    @Binding private var link: URL?
    private let close: () -> Void

    /// close: back to onboarding-welcome. Hidden while a kept recovery waits: the wait ends with the account or startOver.
    init(api: BipeAPIClient, link: Binding<URL?>, close: @escaping () -> Void, onSignedIn: @escaping (AccountSession) -> Void) {
        _model = StateObject(wrappedValue: RecoveryFlowModel(api: api, onSignedIn: onSignedIn))
        _link = link
        self.close = close
    }

    var body: some View {
        NavigationStack(path: $model.path) {
            RecoveryStartView(model: model, close: close)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationDestination(for: RecoveryFlowModel.Route.self) { route in
                    Group {
                        switch route {
                        case .pin: RecoveryPinView(model: model)
                        case .email: RecoveryEmailView(model: model)
                        case .beta: RecoveryBetaView(model: model)
                        case .waiting: RecoveryWaitingView(model: model).navigationBarBackButtonHidden()
                        case .oldPhone: OldPhoneView(onSignedIn: model.signedInWithOldPhone)
                        }
                    }
                    .background(BipeColor.surfaceBase.ignoresSafeArea())
                }
        }
        .tint(BipeColor.accentFill)
        // account-start hands over the link (action openLink), also when the app opened with it.
        .task(id: link) {
            guard let url = link else { return }
            model.openLink(url)
            link = nil
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, model.pending != nil {
                Task { await model.poll() }
            }
        }
    }
}

// MARK: - Shared pieces

private struct RecoveryStep<Content: View>: View {
    let screen: BipeScreen
    let title: String
    var message: String?
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(title)
                    .font(.largeTitle.bold())
                    .foregroundStyle(BipeColor.inkPrimary)
                if let message {
                    Text(message)
                        .font(.body)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                content
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .accessibilityIdentifier(screen.rawValue)
    }
}

private struct FilledButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!enabled)
    }
}

private struct Footer: View {
    let text: String
    var isError = false

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(isError ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
    }
}

// MARK: - recovery-start

private struct RecoveryStartView: View {
    @ObservedObject var model: RecoveryFlowModel
    let close: () -> Void

    var body: some View {
        RecoveryStep(screen: .recoveryStart, title: BipeStrings.recoveryTitle) {
            ForEach(model.rows, id: \.self) { row in
                Button { model.choose(row) } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(BipeLocalized.string(row.titleKey))
                            .font(.headline)
                            .foregroundStyle(BipeColor.inkPrimary)
                        Text(rowBody(of: row))
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.leading)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .toolbar {
            if model.pending == nil {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: close) {
                        PhosphorIcon.caretLeft.image.resizable().frame(width: 20, height: 20)
                    }
                    .accessibilityLabel(BipeStrings.appCancel)
                }
            }
        }
        .onAppear { model.appear() }
    }

    private func rowBody(of row: BipeOnboardingRecoveryRow) -> String {
        row == .email
            ? BipeStrings.recoveryEmailBody(String(BipeRegistries.recoveryDelayHoursEmail))
            : BipeLocalized.string(row.bodyKey)
    }
}

// MARK: - recovery-pin

private struct RecoveryPinView: View {
    @ObservedObject var model: RecoveryFlowModel
    @FocusState private var focused: Bool

    var body: some View {
        RecoveryStep(screen: .recoveryPin, title: title, message: message) {
            if model.pinPhase == .blocked {
                FilledButton(title: BipeStrings.pinForgot) { model.forgotPin() }
            } else {
                ZStack {
                    // The system digit keyboard brings delete and paste.
                    TextField("", text: Binding(get: { model.pin }, set: model.editPin))
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .focused($focused)
                        .opacity(0.02)
                        .accessibilityHidden(true)
                    HStack(spacing: 10) {
                        ForEach(0..<BipeRegistries.pinLength, id: \.self) { index in
                            Circle()
                                .fill(index < model.pin.count ? BipeColor.accentFill : BipeColor.surfaceSunken)
                                .frame(width: 14, height: 14)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                    .onTapGesture { focused = true }
                    // The field is hidden from VoiceOver: the dots are the PIN field, a tap opens the keyboard (bipe#495).
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(title)
                    .accessibilityValue(BipeStrings.a11yPinProgress(String(model.pin.count), String(BipeRegistries.pinLength)))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { focused = true }
                }
                .disabled(model.pinPhase == .checking)
                if model.pinPhase == .checking {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let error = errorText {
                    Footer(text: error, isError: true)
                }
                if model.pinPhase == .failed, model.pinError == BipeStrings.errorRecoveryInvalid {
                    FilledButton(title: BipeStrings.actionContinue) { model.backToStart() }
                }
                Footer(text: BipeStrings.pinNeverAsked)
                if model.pinPhase != .newPin && model.pinPhase != .newPinConfirm {
                    Button { model.forgotPin() } label: {
                        Text(BipeStrings.pinForgot).foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
        }
        .onAppear { focused = true }
    }

    private var title: String {
        switch model.pinPhase {
        case .blocked: BipeStrings.pinBlockedTitle
        case .newPin: BipeStrings.recoveryNewPinTitle
        case .newPinConfirm: BipeStrings.pinConfirmTitle
        default: BipeStrings.pinEnterTitle
        }
    }

    private var message: String? {
        switch model.pinPhase {
        case .blocked: BipeStrings.pinBlockedBody(String(BipeRegistries.pinMaxWrongTries))
        case .newPin: BipeStrings.pinCreateBody(String(BipeRegistries.pinLength))
        case .newPinConfirm: nil
        default: model.factor == .token ? BipeStrings.recoveryTokenFound : nil
        }
    }

    private var errorText: String? {
        if model.newPinMismatch { return BipeStrings.pinMismatch }
        if let problem = model.newPinProblem { return problem.localizedMessage }
        return model.pinError
    }
}

// MARK: - recovery-email

private struct RecoveryEmailView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        RecoveryStep(screen: .recoveryEmail,
                     title: BipeStrings.recoveryEmail,
                     message: BipeStrings.recoveryEmailBody(String(BipeRegistries.recoveryDelayHoursEmail))) {
            if model.emailPhase == .sent {
                Footer(text: BipeStrings.recoveryEmailSent)
            } else {
                TextField(BipeStrings.recoveryEmailField, text: Binding(get: { model.email }, set: model.editEmail))
                    .keyboardType(.emailAddress)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
                if model.emailInvalid {
                    Footer(text: BipeStrings.signupEmailInvalid, isError: true)
                }
                if let error = model.emailError {
                    Footer(text: error, isError: true)
                }
                FilledButton(title: BipeStrings.appRecoveryEmailSend, enabled: model.emailPhase != .sending) {
                    Task { await model.sendEmail() }
                }
                if model.emailPhase == .sending {
                    ProgressView().frame(maxWidth: .infinity)
                }
            }
        }
    }
}

// MARK: - beta sign-in

private struct RecoveryBetaView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        RecoveryStep(screen: .recoveryStart, title: BipeStrings.recoveryBeta, message: BipeStrings.recoveryBetaBody) {
            TextField(BipeStrings.signupEmail, text: Binding(get: { model.betaEmail }, set: model.editBetaEmail))
                .keyboardType(.emailAddress)
                .textContentType(.username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
            SecureField(BipeStrings.recoveryBetaPassword, text: Binding(get: { model.betaPassword }, set: model.editBetaPassword))
                .textContentType(.password)
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceSunken))
            if let error = model.betaError {
                Footer(text: error, isError: true)
            }
            FilledButton(title: BipeStrings.actionContinue, enabled: model.canSubmitBeta) {
                Task { await model.submitBeta() }
            }
            if model.betaBusy {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
    }
}

// MARK: - recovery-waiting

private struct RecoveryWaitingView: View {
    @ObservedObject var model: RecoveryFlowModel

    var body: some View {
        RecoveryStep(screen: .recoveryWaiting, title: BipeStrings.recoveryWaitingTitle) {
            switch model.waitingPhase {
            case .waiting, .claiming:
                if let pending = model.pending {
                    let date = pending.readyAt.formatted(date: .abbreviated, time: .shortened)
                    Text(pending.purpose == .pinReset ? BipeStrings.recoveryWaitingPinBody(date) : BipeStrings.recoveryWaitingBody(date))
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                if model.waitingPhase == .claiming {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(BipeStrings.appStartLoading).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            case .ready:
                if model.pending?.purpose == .pinReset {
                    Text(BipeStrings.recoveryPinReady).foregroundStyle(BipeColor.inkPrimary)
                    FilledButton(title: BipeStrings.actionContinue) { model.startOver() }
                } else {
                    Text(BipeStrings.recoveryReady).foregroundStyle(BipeColor.inkPrimary)
                    Footer(text: BipeStrings.recoveryHistoryNote)
                }
            case .cancelled:
                Text(BipeStrings.recoveryCancelled).foregroundStyle(BipeColor.inkPrimary)
                FilledButton(title: BipeStrings.actionContinue) { model.startOver() }
            case .expired:
                Text(BipeStrings.errorRecoveryInvalid).foregroundStyle(BipeColor.inkPrimary)
                FilledButton(title: BipeStrings.actionContinue) { model.startOver() }
            case .failed:
                Footer(text: BipeStrings.errorNetwork, isError: true)
                FilledButton(title: BipeStrings.appRetry) { Task { await model.poll() } }
            }
        }
        .task { await model.waitingAppear() }
    }
}
