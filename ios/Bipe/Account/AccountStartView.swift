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
                if model.showsRecovery {
                    RecoveryFlowView(api: model.api,
                                     link: $model.recoveryLink,
                                     close: { model.showsRecovery = false },
                                     onSignedIn: model.didRecover)
                } else {
                    // Spec account-start: noAccount shows onboarding-welcome.
                    SignupFlowView(api: model.api,
                                   onSignedUp: { session, outcome in model.didSignUp(session, outcome: outcome) },
                                   onHaveAccount: { model.showsRecovery = true },
                                   onRecoveryLink: model.openLink)
                }
            case .failed:
                StartMessage(icon: .warningCircle, title: BipeStrings.appName, message: BipeStrings.appStartError) {
                    Task { await model.retry() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.accountStart.rawValue)
        .tint(BipeColor.accentFill)
        .onOpenURL { model.openLink($0) }
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.resume() }
            }
        }
    }
}

/// The signed-in app: chat-list, with terms-reaccept over it when the legal version changed, and recovery-alert
/// while someone gets back into this account elsewhere.
private struct SignedInView: View {
    let session: AccountSession
    let api: BipeAPIClient
    let initialRoomId: String?
    let onSignOut: () -> Void
    @StateObject private var terms: TermsReacceptModel
    @StateObject private var recoveryAlert: RecoveryAlertModel
    @State private var showsSettings = false
    @Environment(\.scenePhase) private var scenePhase

    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String?, onSignOut: @escaping () -> Void) {
        self.session = session
        self.api = api
        self.initialRoomId = initialRoomId
        self.onSignOut = onSignOut
        _terms = StateObject(wrappedValue: TermsReacceptModel(api: api, session: session))
        _recoveryAlert = StateObject(wrappedValue: RecoveryAlertModel(api: api, session: session))
    }

    var body: some View {
        ChatListView(session: session, api: api, initialRoomId: initialRoomId) { showsSettings = true }
            .sheet(isPresented: $showsSettings) {
                SettingsHomeView(session: session, api: api, onSignedOut: onSignOut)
            }
            .fullScreenCover(isPresented: Binding(get: { recoveryAlert.notice != nil }, set: { _ in })) {
                RecoveryAlertView(model: recoveryAlert)
            }
            .fullScreenCover(isPresented: Binding(get: { terms.phase != .hidden }, set: { _ in })) {
                TermsReacceptView(model: terms, onSignOut: onSignOut)
            }
            .task { await terms.check() }
            .task { await recoveryAlert.check() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task { await terms.check() }
                    Task { await recoveryAlert.check() }
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
