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
///
/// Opens every account on this phone (ADR 0086): one SDK client and session store per Matrix ID. The current account
/// is the one used last, else the oldest on the phone.
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
    /// The open accounts, oldest first.
    @Published private(set) var sessions: [AccountSession] = []

    /// The current account.
    var session: AccountSession? {
        sessions.first { $0.userId == userId }
    }

    let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    private var starting = false

    /// Action start: open every account on this phone, from its saved session or else its device credential; with no
    /// account, finish a pending sign-up (POST /v1/signup/resume, ADR 0094); else noAccount. Recovery is bipe-chat/bipe#556.
    func start() async {
        guard sessions.isEmpty, !starting else { return }
        starting = true
        defer { starting = false }
        phase = .opening

        let saved = SessionStore.loadAll()
        let credentials = DeviceCredential.all()
        let found = credentials.map(\.userId) + saved.keys.sorted().filter { id in !credentials.contains { $0.userId == id } }
        let accounts = AccountList.merge(found: found)

        // The current account first, so the app shows it as soon as it runs; the others open after it.
        var current: AccountSession?
        var rest = AccountList.preferred(in: accounts)
        while current == nil, !rest.isEmpty {
            let userId = rest.removeFirst()
            current = await open(userId, saved: saved[userId], credential: credentials.first { $0.userId == userId })
        }
        if let current {
            show(current)
            for userId in rest {
                if let session = await open(userId, saved: saved[userId], credential: credentials.first { $0.userId == userId }) {
                    add(session)
                }
            }
            return
        }
        if !accounts.isEmpty {
            phase = .failed
            return
        }
        do {
            if let resumed = try await resumePendingSignup() {
                show(resumed)
            } else {
                phase = .noAccount
            }
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
        for session in sessions {
            await session.syncService.start()
        }
    }

    /// app-tabs switchAccount: makes that account current and saves the choice for the next start.
    func switchAccount(to userId: String) {
        guard sessions.contains(where: { $0.userId == userId }) else { return }
        self.userId = userId
        AccountList.current = userId
    }

    /// signup-create made the account and signed in.
    func didSignUp(_ session: AccountSession, outcome: SignupOutcome) {
        if let inviterUserId = outcome.inviterUserId {
            // The chat with the inviter shows the hint until this account sends them a first message (ADR 0034).
            BipeInviteStore(userID: session.userId)
                .setInviterGreeting(BipeInviterGreeting(userID: inviterUserId, handle: outcome.inviterHandle))
        }
        _ = AccountList.merge(found: [session.userId])
        signupOutcome = outcome
        show(session)
        AccountList.current = session.userId
    }

    /// signup-done finished: chat-list shows, with the inviter's chat open when there is one.
    func finishSignup(openRoomId: String?) {
        initialRoomId = openRoomId
        signupOutcome = nil
    }

    /// The chat that chat-list opens first, once.
    @Published var initialRoomId: String?

    /// The sign-out of terms-reaccept: the current account leaves this phone. Another account on the phone becomes
    /// current; with none left the phone goes back to onboarding.
    func signOut() async {
        guard let session else { return }
        await session.signOut()
        AccountList.remove(session.userId)
        sessions.removeAll { $0.userId == session.userId }
        signupOutcome = nil
        if let next = sessions.first {
            switchAccount(to: next.userId)
        } else {
            userId = nil
            phase = .noAccount
        }
    }

    /// Shows the account without saving it as the choice: an account that failed to open now stays the choice.
    private func show(_ session: AccountSession) {
        add(session)
        userId = session.userId
        phase = .ready
    }

    private func add(_ session: AccountSession) {
        guard !sessions.contains(where: { $0.userId == session.userId }) else { return }
        let order = AccountList.order
        sessions.append(session)
        sessions.sort { (order.firstIndex(of: $0.userId) ?? .max) < (order.firstIndex(of: $1.userId) ?? .max) }
    }

    /// Restores the account's saved session; when there is none, or it no longer works, signs in with the device
    /// credential. `nil` when neither works now.
    private func open(_ userId: String, saved: Session?, credential: DeviceCredential?) async -> AccountSession? {
        if let saved, let session = try? await AccountSession.restore(saved) {
            return session
        }
        guard let credential else { return nil }
        return try? await AccountSession.signIn(userId: credential.userId, password: credential.password)
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
        let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
        store.deletePendingSignup()
        _ = AccountList.merge(found: [session.userId])
        return session
    }
}
