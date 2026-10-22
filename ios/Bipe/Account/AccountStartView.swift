// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec account-start: no login screen (ADR 0057). Shows app-tabs when the current account is ready.
struct AccountStartView: View {
    @StateObject private var model: AccountStartModel
    @StateObject private var tabs: AppTabsModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let model = AccountStartModel()
        _model = StateObject(wrappedValue: model)
        _tabs = StateObject(wrappedValue: AppTabsModel(start: model))
    }

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
                        // One view per account: a switch builds the tabs again for the new account.
                        SignedInView(session: session, tabs: tabs, api: model.api, initialRoomId: model.initialRoomId) {
                            Task { await model.signOut() }
                        }
                        .id(session.userId)
                    }
                }
            case .opening:
                StartMessage(icon: .chatCircle, title: BipeStrings.appName, message: BipeStrings.appStartLoading, showsProgress: true)
            case .noAccount:
                // Spec account-start: noAccount shows onboarding-welcome.
                SignupFlowView(api: model.api) { session, outcome in
                    model.didSignUp(session, outcome: outcome)
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

/// The signed-in app: app-tabs, with terms-reaccept over it when the legal version changed.
private struct SignedInView: View {
    let session: AccountSession
    let tabs: AppTabsModel
    let api: BipeAPIClient
    let initialRoomId: String?
    let onSignOut: () -> Void
    @StateObject private var terms: TermsReacceptModel
    @Environment(\.scenePhase) private var scenePhase

    init(session: AccountSession, tabs: AppTabsModel, api: BipeAPIClient, initialRoomId: String?, onSignOut: @escaping () -> Void) {
        self.session = session
        self.tabs = tabs
        self.api = api
        self.initialRoomId = initialRoomId
        self.onSignOut = onSignOut
        _terms = StateObject(wrappedValue: TermsReacceptModel(api: api, session: session))
    }

    var body: some View {
        AppTabsView(model: tabs, session: session, api: api, initialRoomId: initialRoomId)
            .fullScreenCover(isPresented: Binding(get: { terms.phase != .hidden }, set: { _ in })) {
                TermsReacceptView(model: terms, onSignOut: onSignOut)
            }
            .task { await terms.check() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task {
                        await terms.check()
                        await tabs.refreshProfiles()
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
