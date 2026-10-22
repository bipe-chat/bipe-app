// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// What signup-done needs after a new account (spec signup-done).
struct SignupOutcome: Equatable {
    var inviterUserId: String?
    var inviterHandle: String?
}

/// Spec account-start (bipe-shared product/screens/account-start.json).
@MainActor
final class AccountStartModel: ObservableObject {
    enum Phase: Equatable {
        case opening
        case noAccount
        case failed
        case ready
    }

    @Published private(set) var phase: Phase = .opening
    @Published private(set) var userId: String?
    /// Set right after a sign-up: the app shows signup-done before chat-list.
    @Published var signupOutcome: SignupOutcome?
    /// noAccount shows recovery-start instead of onboarding-welcome: after "Já tenho conta", or while a kept
    /// recovery waits (spec recovery-waiting notes).
    @Published var showsRecovery = BipeCredentialStore().pendingRecovery() != nil
    /// A bipe.chat/recuperar link the app was opened with (recovery-email, action openLink).
    @Published var recoveryLink: URL?

    /// Routes a recovery link to the recovery flow. A signed-in phone ignores it: noAccount alone shows the flow.
    func openLink(_ url: URL) {
        guard BipeRecoveryLink.token(from: url) != nil else { return }
        recoveryLink = url
        showsRecovery = true
    }
    private(set) var session: AccountSession? {
        didSet {
            // Spec push-registration: keep this phone's pusher current; notification actions use the account.
            if let session, session !== oldValue {
                PushRegistration.shared.attach(session)
                NotificationActions.shared.attach(session)
            }
        }
    }

    let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)

    /// Action start: restore the saved session; else finish a pending sign-up (POST /v1/signup/resume, ADR 0094);
    /// else sign in with the device credential; else noAccount. Recovery is bipe-chat/bipe#556.
    func start() async {
        guard session == nil else { return }
        phase = .opening
        do {
            if let saved = SessionStore.load() {
                userId = saved.userId
                session = try await AccountSession.restore(saved)
            } else if let resumed = try await resumePendingSignup() {
                session = resumed
            } else if let credential = DeviceCredential.newest() {
                userId = credential.userId
                session = try await AccountSession.signIn(userId: credential.userId, password: credential.password)
            } else {
                phase = .noAccount
                return
            }
            phase = .ready
        } catch {
            phase = .failed
        }
    }

    /// Action retry: from failed, run start again.
    func retry() async {
        guard phase == .failed else { return }
        await start()
    }

    /// Starts the sync again when the app comes back to the foreground.
    func resume() async {
        await session?.syncService.start()
        await PushRegistration.shared.appBecameActive()
    }

    /// signup-create made the account and signed in.
    func didSignUp(_ session: AccountSession, outcome: SignupOutcome) {
        self.session = session
        userId = session.userId
        if let inviterUserId = outcome.inviterUserId {
            // The chat with the inviter shows the hint until this account sends them a first message (ADR 0034).
            BipeInviteStore(userID: session.userId)
                .setInviterGreeting(BipeInviterGreeting(userID: inviterUserId, handle: outcome.inviterHandle))
        }
        signupOutcome = outcome
        phase = .ready
    }

    /// A recovery path signed in (recovery-pin, recovery-waiting or the beta sign-in): chat-list, then encryption-status.
    func didRecover(_ session: AccountSession) {
        self.session = session
        userId = session.userId
        showsRecovery = false
        phase = .ready
    }

    /// signup-done finished: chat-list shows, with the inviter's chat open when there is one.
    func finishSignup(openRoomId: String?) {
        initialRoomId = openRoomId
        signupOutcome = nil
    }

    /// The chat that chat-list opens first, once.
    @Published var initialRoomId: String?

    /// The sign-out of terms-reaccept: the phone goes back to onboarding.
    func signOut() async {
        guard let session else { return }
        // Spec push-registration, action signOut: the pusher goes before the session ends.
        await PushRegistration.shared.signOut()
        await session.signOut()
        self.session = nil
        userId = nil
        signupOutcome = nil
        phase = .noAccount
    }

    /// A sign-up whose answer never reached this phone (ADR 0094). `nil` when there is nothing to finish.
    private func resumePendingSignup() async throws -> AccountSession? {
        let store = BipeCredentialStore()
        guard let pending = store.pendingSignup() else { return nil }
        let response: BipeSignupResponse
        do {
            response = try await api.resumeSignup(signupKey: pending.key)
        } catch {
            if case .server(.notFound, _, _) = error {
                store.deletePendingSignup()
            }
            return nil
        }
        guard let credentials = response.credentials, store.save(credentials) else { return nil }
        userId = credentials.userId
        let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
        store.deletePendingSignup()
        return session
    }
}
