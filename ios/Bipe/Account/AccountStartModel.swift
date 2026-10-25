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

private struct AccountStartupSnapshot: Sendable {
    let saved: [String: Session]
    let credentials: [DeviceCredential]
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

    @Published private(set) var phase: Phase = .opening {
        didSet { AccountStep.log.notice("phase: \(String(describing: self.phase), privacy: .public)") }
    }
    @Published private(set) var userId: String?
    /// Set right after a sign-up: the app shows signup-done before chat-list.
    @Published var signupOutcome: SignupOutcome?
    /// The open accounts, oldest first.
    @Published private(set) var sessions: [AccountSession] = [] {
        // accountAvailable of spec pix-check-result, for the share extension.
        didSet { PixHandoff.accountAvailable = !sessions.isEmpty }
    }

    /// Spec soft-logout needsPin: the account whose token the server refused and that the hidden password cannot
    /// bring back. The app shows recovery-pin for it.
    @Published var needsPin: String?
    /// Spec soft-logout removed: shown once.
    @Published var notice: String?
    private var signingInAgain: Set<String> = []

    /// The current account.
    var session: AccountSession? {
        sessions.first { $0.userId == userId }
    }

    /// noAccount shows recovery-start instead of onboarding-welcome: after "Já tenho conta", or while a kept
    /// recovery waits (spec recovery-waiting notes).
    @Published var showsRecovery = BipeCredentialStore().pendingRecovery() != nil
    /// A bipe.chat/recuperar link the app was opened with (recovery-email, action openLink).
    @Published var recoveryLink: URL?
    /// Spec account-start legacyKept: noAccount, and the earlier Bipe app's data is on this phone (bipe-chat/bipe#603).
    @Published private(set) var legacyKept = false
    /// Action startFresh: onboarding-welcome for this launch, although legacyKept.
    @Published private(set) var startedFresh = false

    /// Action recoverLegacy: recovery-start, where the earlier app's synced recovery token gets the account back.
    func recoverLegacy() {
        showsRecovery = true
    }

    /// Action startFresh. The legacy data stays; the next launch checks again.
    func startFresh() {
        startedFresh = true
    }

    /// Routes a recovery link to the recovery flow. A signed-in phone ignores it: noAccount alone shows the flow.
    func openLink(_ url: URL) {
        guard BipeRecoveryLink.token(from: url) != nil else { return }
        recoveryLink = url
        showsRecovery = true
    }
    /// Spec push-registration: keep this phone's pusher on the current account.
    private func attachServices(_ session: AccountSession) {
        guard attachedUserId != session.userId else { return }
        attachedUserId = session.userId
        PushRegistration.shared.attach(session)
    }
    private var attachedUserId: String?

    let api = BipeAPIClient(baseURL: AppConfig.apiBaseURL)
    private var starting = false

    /// Action start: open every account on this phone, from its saved session or else its device credential; with no
    /// account, finish a pending sign-up (POST /v1/signup/resume, ADR 0094); else noAccount. Recovery is bipe-chat/bipe#556.
    func start() async {
        guard sessions.isEmpty, !starting else { return }
        starting = true
        defer { starting = false }
        phase = .opening

        let snapshot: AccountStartupSnapshot
        do {
            snapshot = try await AccountStep.run("read saved accounts") {
                AccountStartupSnapshot(saved: SessionStore.loadAll(), credentials: DeviceCredential.all())
            }
        } catch {
            if !Task.isCancelled { phase = .failed }
            return
        }
        let saved = snapshot.saved
        let credentials = snapshot.credentials
        let found = credentials.map(\.userId) + saved.keys.sorted().filter { id in !credentials.contains { $0.userId == id } }
        let accounts = AccountList.merge(found: found)

        // The current account first, so the app shows it as soon as it runs; the others open after it.
        var current: AccountSession?
        var rest = AccountList.preferred(in: accounts)
        while current == nil, !rest.isEmpty {
            guard !Task.isCancelled else { return }
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
            if !Task.isCancelled { phase = .failed }
            return
        }
        do {
            if let resumed = try await resumePendingSignup() {
                show(resumed)
            } else {
                legacyKept = LegacyData.isPresent
                phase = .noAccount
            }
        } catch {
            if !Task.isCancelled { phase = .failed }
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
            if pendingAuthErrors.remove(session.userId) != nil {
                await authError(session, isSoftLogout: true)
            } else {
                await session.syncService.start()
            }
        }
        await PushRegistration.shared.appBecameActive()
    }

    /// app-tabs switchAccount: makes that account current and saves the choice for the next start.
    func switchAccount(to userId: String) {
        guard let session = sessions.first(where: { $0.userId == userId }) else { return }
        self.userId = userId
        AccountList.current = userId
        attachServices(session)
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

    /// A recovery path signed in (recovery-pin, recovery-waiting or the beta sign-in): chat-list, then encryption-status.
    func didRecover(_ session: AccountSession) {
        _ = AccountList.merge(found: [session.userId])
        showsRecovery = false
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
    func signOut(userId targetId: String? = nil, alreadyLoggedOut: Bool = false) async {
        let id = targetId ?? userId
        guard let session = sessions.first(where: { $0.userId == id }) else { return }
        NotificationActions.shared.detach(userId: session.userId)
        NotificationKeys.accountRoutes?.remove(userId: session.userId)
        sessions.removeAll { $0 === session }
        if attachedUserId == session.userId {
            // Spec push-registration, action signOut: the pusher goes before the session ends.
            attachedUserId = nil
            await PushRegistration.shared.signOut()
        }
        await session.signOut(alreadyLoggedOut: alreadyLoggedOut)
        AccountList.remove(session.userId)
        guard userId == session.userId else { return }
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
        attachServices(session)
        phase = .ready
    }

    private func add(_ session: AccountSession) {
        guard !sessions.contains(where: { $0.userId == session.userId }) else { return }
        let order = AccountList.order
        watchAuthErrors(session)
        NotificationActions.shared.attach(session)
        Task { await KeychainMove.report(session, api: api) }
        Task { await PhoneReport.report(session, api: api) }
        sessions.append(session)
        sessions.sort { (order.firstIndex(of: $0.userId) ?? .max) < (order.firstIndex(of: $1.userId) ?? .max) }
    }

    // MARK: - soft-logout

    private func watchAuthErrors(_ session: AccountSession) {
        session.onAuthError = { [weak self, weak session] isSoftLogout in
            guard let self, let session else { return }
            Task { await self.authError(session, isSoftLogout: isSoftLogout) }
        }
    }

    /// Action authError of spec soft-logout.
    func authError(_ session: AccountSession, isSoftLogout: Bool) async {
        guard sessions.contains(where: { $0 === session }), !signingInAgain.contains(session.userId) else { return }
        let name = Self.name(of: session.userId)
        // Spec account-delete notes: the session ended after the due time of a deletion, so the server deleted the
        // account. Remove it from this phone like a sign-out and say so.
        if DeletionDate.isPast(for: session.userId) {
            AccountStep.log.notice("account deleted on the server")
            await signOut(userId: session.userId, alreadyLoggedOut: true)
            DeletionDate.remember(nil, for: session.userId)
            notice = BipeStrings.deletionDone
            return
        }
        guard isSoftLogout else {
            // Another device signed this phone out: never sign in again by itself (spec soft-logout notes).
            AccountStep.log.notice("soft-logout: device signed out on the server")
            await signOut(userId: session.userId, alreadyLoggedOut: true)
            notice = BipeStrings.softLogoutRemoved(name)
            return
        }
        signingInAgain.insert(session.userId)
        defer { signingInAgain.remove(session.userId) }
        guard let password = DeviceCredential.all().first(where: { $0.userId == session.userId })?.password else {
            await askForPin(session)
            return
        }
        AccountStep.log.notice("soft-logout: signing in again")
        await session.pause()
        do {
            let fresh = try await AccountSession.signInAgain(after: session, password: password)
            replace(session, with: fresh)
        } catch {
            if AccountSession.isRefused(error) {
                await askForPin(session)
            } else {
                // Offline or a local failure: keep everything and try again on the next foreground.
                AccountStep.log.error("soft-logout: sign-in failed, kept for later")
                pendingAuthErrors.insert(session.userId)
                await session.syncService.start()
            }
        }
    }

    /// Accounts whose quiet sign-in failed for a reason other than a refused password.
    private var pendingAuthErrors: Set<String> = []

    /// Spec soft-logout needsPin. Only the refused saved session goes, so recovery-start offers the synced token.
    private func askForPin(_ session: AccountSession) async {
        AccountStep.log.notice("soft-logout: PIN needed")
        await session.pause()
        SessionStore.delete(userId: session.userId)
        needsPin = session.userId
    }

    /// A good PIN brought the account back (recovery-pin submit from needsPin).
    func didEnterPin(_ fresh: AccountSession) {
        needsPin = nil
        if let old = sessions.first(where: { $0.userId == fresh.userId }) {
            replace(old, with: fresh)
        } else {
            didRecover(fresh)
        }
    }

    private func replace(_ old: AccountSession, with fresh: AccountSession) {
        guard let index = sessions.firstIndex(where: { $0 === old }) else { return }
        NotificationActions.shared.detach(userId: old.userId)
        watchAuthErrors(fresh)
        NotificationActions.shared.attach(fresh)
        sessions[index] = fresh
        if attachedUserId == fresh.userId {
            attachedUserId = nil
            attachServices(fresh)
        }
    }

    private static func name(of userId: String) -> String {
        let label = BipeAccountLabels.label(for: userId)
        return label == userId ? BipeStrings.tabYou : label
    }

    /// Restores the account's saved session; when there is none, or it no longer works, signs in with the device
    /// credential. `nil` when neither works now.
    private func open(_ userId: String, saved: Session?, credential: DeviceCredential?) async -> AccountSession? {
        let log = AccountStep.log
        if let saved {
            do {
                return try await AccountSession.restore(saved)
            } catch AccountStep.Failure.timedOut(_) {
                // A timed-out restore may still hold its SDK store. Do not delete that store and sign in over it.
                log.notice("restore timed out; saved session retained")
                return nil
            } catch is CancellationError {
                return nil
            } catch AccountStep.Failure.noStore {
                // A saved session from an earlier install: drop it and sign in again.
                log.notice("saved session without its store, dropped")
                SessionStore.delete(userId: userId)
            } catch {
                // A local restore failure does not establish that this saved session is invalid.
                // In particular, never delete its database to try password login after a transient error.
                log.error("restore failed; saved session and store retained")
                return nil
            }
        }
        guard let credential else {
            log.notice("no device credential for this account")
            return nil
        }
        do {
            return try await AccountSession.signIn(userId: credential.userId, password: credential.password)
        } catch {
            log.error("sign-in failed")
            return nil
        }
    }

    /// A sign-up whose answer never reached this phone (ADR 0094). `nil` when there is nothing to finish.
    private func resumePendingSignup() async throws -> AccountSession? {
        guard let pending = try await AccountStep.run("read pending signup", {
            BipeCredentialStore().pendingSignup()
        }) else { return nil }
        let response: BipeSignupResponse
        let api = api
        do {
            response = try await AccountStep.run("resume signup") {
                try await api.resumeSignup(signupKey: pending.key)
            }
        } catch {
            if let apiError = error as? BipeAPIError, case .server(.notFound, _, _) = apiError {
                try await AccountStep.run("remove pending signup") { BipeCredentialStore().deletePendingSignup() }
            }
            if error is CancellationError || error is AccountStep.Failure { throw error }
            return nil
        }
        guard let credentials = response.credentials else { return nil }
        guard try await AccountStep.run("save signup credential", { BipeCredentialStore().save(credentials) }) else { return nil }
        let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
        try await AccountStep.run("remove pending signup") { BipeCredentialStore().deletePendingSignup() }
        _ = AccountList.merge(found: [session.userId])
        return session
    }
}
