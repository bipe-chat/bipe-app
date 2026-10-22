// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-start: no login screen (ADR 0057). Shows chat-list when the account is ready.
struct AccountStartView: View {
    @StateObject private var model = AccountStartModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch model.phase {
            case .ready:
                if let session = model.session {
                    if let outcome = model.signupOutcome {
                        SignupDoneView(session: session, outcome: outcome) { roomId in
                            model.finishSignup(openRoomId: roomId)
                        }
                    } else {
                        SignedInView(session: session, api: model.api, initialRoomId: model.initialRoomId) {
                            Task { await model.signOut() }
                        }
                    }
                }
            case .opening:
                StartMessage(icon: .chatCircle, title: BipeStrings.appName, message: BipeStrings.appStartLoading, showsProgress: true)
            case .noAccount:
                if model.hasPendingRecovery {
                    // Spec account-start: a kept recovery opens recovery-waiting instead of noAccount.
                    RecoveryWaitingView(api: model.api,
                                        onSignedIn: { model.didRecover($0) },
                                        onStartOver: { model.startRecoveryOver() })
                } else {
                    // Spec account-start: noAccount shows onboarding-welcome.
                    SignupFlowView(api: model.api,
                                   onSignedUp: { session, outcome in model.didSignUp(session, outcome: outcome) },
                                   onSignedIn: { model.didRecover($0) },
                                   onWaiting: { model.didStartRecovery() })
                }
            case .failed:
                StartMessage(icon: .warningCircle, title: BipeStrings.appName, message: BipeStrings.appStartError) {
                    Task { await model.retry() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.accountStart.rawValue)
        .tint(BipeColor.accentFill)
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.resume() }
            }
        }
    }
}

/// The signed-in app: chat-list, with terms-reaccept, recovery-alert and an incoming device-verification over it,
/// and the encryption-status sheets.
private struct SignedInView: View {
    let session: AccountSession
    let api: BipeAPIClient
    let initialRoomId: String?
    let onSignOut: () -> Void
    @StateObject private var terms: TermsReacceptModel
    @StateObject private var alert: RecoveryAlertModel
    @StateObject private var encryption: EncryptionModel
    @StateObject private var verification: DeviceVerificationModel
    @Environment(\.scenePhase) private var scenePhase

    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String?, onSignOut: @escaping () -> Void) {
        self.session = session
        self.api = api
        self.initialRoomId = initialRoomId
        self.onSignOut = onSignOut
        _terms = StateObject(wrappedValue: TermsReacceptModel(api: api, session: session))
        _alert = StateObject(wrappedValue: RecoveryAlertModel(api: api, session: session))
        _encryption = StateObject(wrappedValue: EncryptionModel(session: session))
        _verification = StateObject(wrappedValue: DeviceVerificationModel(session: session))
    }

    var body: some View {
        ZStack {
            ChatListView(session: session, api: api, initialRoomId: initialRoomId)
                .environmentObject(encryption)
            if verification.isActive {
                DeviceVerificationView(model: verification)
            }
            if alert.phase != .hidden {
                RecoveryAlertView(model: alert)
            }
            if terms.phase != .hidden {
                TermsReacceptView(model: terms, onSignOut: onSignOut)
            }
        }
        .sheet(isPresented: $encryption.sheetOpen) { HistorySheet(model: encryption) }
        .sheet(isPresented: $encryption.showsKeyEnter) {
            RecoveryKeyEnterView(encryption: encryption.encryption) {
                encryption.openSheet()
            }
        }
        .sheet(isPresented: $encryption.showsKeySave) { RecoveryKeySaveView(encryption: encryption.encryption) }
        .onChange(of: encryption.showsVerification) { _, shows in
            if shows {
                encryption.showsVerification = false
                Task { await verification.request() }
            }
        }
        .task { await terms.check() }
        .task { await alert.check() }
        .task { await encryption.observe() }
        .task { await verification.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await terms.check()
                    await alert.check()
                }
            }
        }
    }
}

private struct StartMessage: View {
    let icon: PhosphorIcon
    let title: String
    let message: String
    var showsProgress = false
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            icon.image
                .resizable()
                .frame(width: 56, height: 56)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(title)
                .font(.title.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            if showsProgress {
                ProgressView()
            }
            if let retry {
                Button(action: retry) {
                    Label {
                        Text(BipeStrings.appRetry)
                    } icon: {
                        PhosphorIcon.arrowClockwise.image.resizable().frame(width: 18, height: 18)
                    }
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
    }
}
