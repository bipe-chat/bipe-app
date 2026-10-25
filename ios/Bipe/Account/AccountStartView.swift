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
    @ObservedObject private var notifications = NotificationActions.shared

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
                        // A quiet sign-in after soft-logout replaces the session: build the tabs again too.
                        .id(ObjectIdentifier(session))
                    }
                }
            case .opening:
                StartMessage(icon: nil, title: BipeStrings.appName, message: BipeStrings.appStartLoading, showsProgress: true)
            case .noAccount:
                if model.showsRecovery {
                    RecoveryFlowView(api: model.api,
                                     link: $model.recoveryLink,
                                     close: { model.showsRecovery = false },
                                     onSignedIn: model.didRecover)
                } else if model.legacyKept, !model.startedFresh {
                    // Spec account-start legacyKept: never onboarding-welcome as if the phone had no account.
                    LegacyKeptView(recover: model.recoverLegacy, startFresh: model.startFresh)
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
        .toast($model.notice)
        // Spec soft-logout needsPin: recovery-pin for that account, "Digite sua senha para continuar".
        .fullScreenCover(item: Binding(get: { model.needsPin.map(SoftLogoutTarget.init) }, set: { if $0 == nil { model.needsPin = nil } })) { target in
            RecoveryFlowView(api: model.api, link: .constant(nil), softLogoutUserId: target.userId,
                             close: { model.needsPin = nil }, onSignedIn: model.didEnterPin)
        }
        .onOpenURL { model.openLink($0) }
        .onReceive(notifications.$roomToOpen) { target in
            Task { @MainActor in routeNotification(target) }
        }
        .onReceive(model.$sessions) { _ in
            // Published values arrive before assignment; route after this update.
            Task { @MainActor in routeNotification(notifications.roomToOpen) }
        }
        .task { await model.start() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.resume() }
            }
        }
    }

    private func routeNotification(_ pending: BipeNotificationOpen?) {
        guard let target = pending,
              model.sessions.contains(where: { $0.userId == target.accountUserId }) else { return }
        model.switchAccount(to: target.accountUserId)
        tabs.selectTab(target.destination == .groups ? .grupos : .conversas)
    }
}

/// The signed-in app: app-tabs, with terms-reaccept over it when the legal version changed, and recovery-alert
/// while someone gets back into this account elsewhere.
private struct SignedInView: View {
    let session: AccountSession
    let tabs: AppTabsModel
    let api: BipeAPIClient
    let initialRoomId: String?
    let onSignOut: () -> Void
    @StateObject private var terms: TermsReacceptModel
    @StateObject private var recoveryAlert: RecoveryAlertModel
    @StateObject private var phoneNotice: PhoneConfirmNoticeModel
    @Environment(\.scenePhase) private var scenePhase

    init(session: AccountSession, tabs: AppTabsModel, api: BipeAPIClient, initialRoomId: String?, onSignOut: @escaping () -> Void) {
        self.session = session
        self.tabs = tabs
        self.api = api
        self.initialRoomId = initialRoomId
        self.onSignOut = onSignOut
        _terms = StateObject(wrappedValue: TermsReacceptModel(api: api, session: session))
        _recoveryAlert = StateObject(wrappedValue: RecoveryAlertModel(api: api, session: session))
        _phoneNotice = StateObject(wrappedValue: PhoneConfirmNoticeModel(api: api, session: session))
    }

    var body: some View {
        AppTabsView(model: tabs, session: session, api: api, initialRoomId: initialRoomId)
            .fullScreenCover(isPresented: Binding(get: { recoveryAlert.notice != nil || recoveryAlert.linkNotice != nil }, set: { _ in })) {
                RecoveryAlertView(model: recoveryAlert)
            }
            .fullScreenCover(isPresented: Binding(get: { terms.phase != .hidden }, set: { _ in })) {
                TermsReacceptView(model: terms, onSignOut: onSignOut)
            }
            .task { await terms.check() }
            .task { await recoveryAlert.check() }
            .task { await phoneNotice.check() }
            // Spec phone-link-status, checkNotice: one notice per confirm_by.
            .alert(BipeStrings.phoneConfirmNoticeTitle,
                   isPresented: Binding(get: { phoneNotice.notice != nil }, set: { if !$0 { phoneNotice.dismiss() } }),
                   presenting: phoneNotice.notice) { _ in
                Button(BipeStrings.actionContinue) { phoneNotice.dismiss() }
            } message: { state in
                Text(state.summary)
            }
            // Spec work-hours notes: the phone's copy of a work account's opening hours, for the extension.
            .task { _ = await WorkHoursSync.refresh(session: session, api: api) }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    Task {
                        await terms.check()
                        await tabs.refreshProfiles()
                    }
                    Task { await recoveryAlert.check() }
                    Task { await phoneNotice.check() }
                    Task { _ = await WorkHoursSync.refresh(session: session, api: api) }
                }
            }
    }
}

private struct SoftLogoutTarget: Identifiable {
    let userId: String
    var id: String { userId }
}

private struct StartMessage: View {
    /// nil shows the Bipe mark.
    let icon: PhosphorIcon?
    let title: String
    let message: String
    var showsProgress = false
    var retry: (() -> Void)?

    var body: some View {
        VStack(spacing: 16) {
            Group {
                if let icon {
                    icon.image
                        .resizable()
                        .foregroundStyle(BipeColor.accentInk)
                } else {
                    BipeMark().fill(BipeColor.accentFill)
                }
            }
            .frame(width: 56, height: 56)
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

/// Spec account-start legacyKept (bipe-chat/bipe#603): the earlier Bipe app's data is on this phone and was not opened.
/// Recovery comes first; a new account stays possible, and nothing is deleted.
private struct LegacyKeptView: View {
    let recover: () -> Void
    let startFresh: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            PhosphorIcon.lockSimple.image
                .resizable()
                .frame(width: 56, height: 56)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            Text(BipeStrings.appStartLegacyTitle)
                .font(.title.bold())
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
            Text(BipeStrings.appStartLegacyBody)
                .font(.body)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
            Button(action: recover) {
                Text(BipeStrings.appStartLegacyRecover)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button(action: startFresh) {
                Text(BipeStrings.appStartLegacyFresh)
            }
            .buttonStyle(.borderless)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
