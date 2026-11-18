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
    /// A sign-up with a group link code (ADR 0184): the group the account was invited into.
    var groupRoomId: String?
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
    /// One model for the app: the window shows it, and CallKit opens the accounts with it when PushKit woke the app
    /// without a window (bipe-chat/bipe#760).
    static let shared = AccountStartModel()

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
        didSet {
            PixHandoff.accountAvailable = !sessions.isEmpty
            // Spec share-to-chat: signed out, nothing is kept.
            if sessions.isEmpty && !oldValue.isEmpty { ShareHandoff.deleteAll() }
        }
    }

    /// Spec soft-logout needsPin: the account whose token the server refused. The phone keeps no hidden password
    /// (ADR 0283), so the app shows recovery-pin for it.
    @Published var needsPin: String?
    /// Spec soft-logout removed: shown once.
    @Published var notice: String?

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
        // No account on screen: no flow reads an opened file, so its copy in Documents/Inbox goes (bipe-chat/bipe#1368).
        // A signed-in phone sweeps in app-tabs, after the reader.
        if url.isFileURL, phase != .ready { GovbrInbox.sweepInbox() }
        // A claim link waits for the account, through sign-up (ADR 0195 point 7).
        if ShopClaim.keep(url) { return }
        // Spec group-link-open notes: with no account on screen, a group link waits through sign-up or recovery.
        // A signed-in phone routes it in app-tabs.
        if phase != .ready, GroupLinkHold.keep(url) { return }
        guard BipeRecoveryLink.token(from: url) != nil else { return }
        recoveryLink = url
        showsRecovery = true
    }
    /// The services that follow the current account.
    private func attachServices(_ session: AccountSession) {
        guard attachedUserId != session.userId else { return }
        attachedUserId = session.userId
        // Spec timeline-media notes: show-media and invite photos follow the current account (MSC4278).
        MediaPreviewSync.shared.attach(session)
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
        // Spec interno notes: a member identity's session is a second session under its work account, never an account
        // of its own (ADR 0284 point 5). InternoSessions restores it.
        var saved = snapshot.saved.filter { !BipeMemberIdentity.isMember($0.key) }
        // ADR 0283: the phone keeps no hidden password, so an account opens only from a saved session with its store,
        // or with a password that a sign-up or recovery just stored. An account with neither needs recovery:
        // recovery-pin (needsPin) when its store is still here (soft logout), else recovery-start (a reinstall).
        let records = snapshot.credentials
        let passwordless = Set(records.filter { $0.password == nil }.map(\.userId))
        let lost = Set(saved.keys).union(passwordless).filter { id in
            !records.contains { $0.userId == id && $0.password != nil } && !(saved[id] != nil && AccountSession.hasStore(userId: id))
        }
        for id in lost where saved[id] != nil {
            // A saved session without its store cannot restore (the keychain outlives a deleted app).
            SessionStore.delete(userId: id)
            saved[id] = nil
        }
        let credentials = records.filter { !lost.contains($0.userId) }
        let stranded = lost.sorted().first { AccountSession.hasStore(userId: $0) }
        defer {
            if !Task.isCancelled, needsPin == nil, !lost.isEmpty {
                if let stranded { needsPin = stranded } else if phase == .noAccount { showsRecovery = true }
            }
        }
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
        for session in sessions where session.userId != needsPin {
            await session.syncService.start()
            await session.resumeSending()
        }
        await InternoSessions.shared.resume()
        await PushRegistration.shared.appBecameActive()
    }

    /// app-tabs switchAccount: makes that account current and saves the choice for the next start.
    func switchAccount(to userId: String) {
        guard let session = sessions.first(where: { $0.userId == userId }) else { return }
        self.userId = userId
        AccountList.current = userId
        attachServices(session)
        // Spec location-send notes, end: a switch to another account stops the live location shares.
        LiveLocationCenter.shared.accountChanged(to: userId)
    }

    /// signup-create made the account and signed in.
    func didSignUp(_ session: AccountSession, outcome: SignupOutcome) {
        if let inviterUserId = outcome.inviterUserId {
            // The chat with the inviter shows the hint until this account sends them a first message (ADR 0034).
            BipeInviteStore(userID: session.userId)
                .setInviterGreeting(BipeInviterGreeting(userID: inviterUserId, handle: outcome.inviterHandle))
        }
        // Spec bring-people: the first sheet shows only on the phone that made the account.
        BipeBringPeopleMemory(userID: session.userId).markSignedUp()
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
        // Spec signup-done continue: the group opened, so the held link is done. Otherwise app-tabs opens
        // group-link-open with it and join runs again.
        if let group = signupOutcome?.groupRoomId, group == openRoomId { GroupLinkHold.clear() }
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
        // Spec location-send notes, end: sign-out stops this account's live location shares.
        await LiveLocationCenter.shared.stopAll(userId: session.userId)
        // Spec interno: the member session of a work account leaves with it, before the account's pusher goes.
        await InternoSessions.shared.signOut(forWork: session.userId, alreadyLoggedOut: alreadyLoggedOut)
        if attachedUserId == session.userId { attachedUserId = nil }
        // Spec push-registration, action signOut: this account's pusher goes before the session ends; the other
        // accounts keep theirs.
        await PushRegistration.shared.remove(userId: session.userId)
        await session.signOut(alreadyLoggedOut: alreadyLoggedOut)
        // bipe-chat/bipe#1370: no decrypted media and no recent searches of this account stay. The media folders are
        // one for all accounts; another account on the phone downloads its media again. Here, not in AccountSession:
        // the notification service extension builds AccountSession without the media code.
        await MediaLoader.forgetAll()
        await StorageMeter.deleteAppMedia()
        RecentSearches.forget(userId: session.userId)
        AccountList.remove(session.userId)
        CompanyRole.remember(nil, userId: session.userId)
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
        // Spec push-registration sessionAdded: every account on the phone has its own pusher, open or not.
        PushRegistration.shared.add(session)
        Task { await KeychainMove.report(session, api: api) }
        // Spec account-start deviceKey (ADR 0283).
        Task { await DeviceKeyRegistration.run(session, api: api) }
        Task { await PhoneReport.report(session, api: api) }
        Task { await LiveLocationCenter.shared.cleanUp(session) }
        // Spec interno: a work account's member session restores like other sessions. Until this phone has stored a
        // working recovery key, the key steps call bipe-api in the background, once per launch.
        Task { await InternoSessions.shared.restore(forWork: session, api: session.business(api)) }
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
        guard sessions.contains(where: { $0 === session }), needsPin != session.userId else { return }
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
        // Spec company-team: the owner removed this person. A person has no PIN and no password to sign in again with.
        if CompanyRole.isAttendant(session.userId) {
            AccountStep.log.notice("removed from a work account")
            await signOut(userId: session.userId, alreadyLoggedOut: true)
            notice = BipeStrings.teamRemoved(name)
            return
        }
        guard isSoftLogout else {
            // Another device signed this phone out: never sign in again by itself (spec soft-logout notes).
            AccountStep.log.notice("soft-logout: device signed out on the server")
            await signOut(userId: session.userId, alreadyLoggedOut: true)
            notice = BipeStrings.softLogoutRemoved(name)
            return
        }
        // ADR 0283 decision 5: the phone keeps no hidden password and never signs in again by itself, also when a
        // password from before ADR 0283 is still stored. Somebody ended the session: recovery.
        await askForPin(session)
    }

    /// Spec soft-logout needsPin. Only the refused saved session goes, so recovery-start offers the synced token. The
    /// store stays, so the next launch asks for the PIN again.
    private func askForPin(_ session: AccountSession) async {
        AccountStep.log.notice("soft-logout: PIN needed")
        await session.pause()
        SessionStore.delete(userId: session.userId)
        // A password from before ADR 0283 must not sign the account in again at the next launch. The record stays.
        BipeCredentialStore().forgetPassword(for: session.userId)
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

    /// Spec data-use clearMediaCache (bipe-chat/bipe#826): stops the account's sync, clears the SDK caches and the
    /// app's media folders, then builds the session again from the saved session, as after soft-logout. Keys and the
    /// session stay. Throws when the session cannot be built again; the next start restores it.
    func clearCaches(_ session: AccountSession) async throws {
        guard sessions.contains(where: { $0 === session }) else { return }
        let saved = try session.client.session()
        let before = await StorageMeter.measure(client: session.client)
        await session.pause()
        do {
            try await session.client.clearCaches(syncService: session.syncService)
        } catch {
            AccountStep.log.error("clear caches failed, building the session again")
        }
        await MediaLoader.forgetAll()
        await StorageMeter.deleteAppMedia()
        let fresh = try await AccountSession.restore(saved)
        replace(session, with: fresh)
        // freedBytes: the tabs build again for the new session, so the toast of the app says it.
        let after = await StorageMeter.measure(client: fresh.client)
        let freed = max(0, before.total - after.total)
        notice = BipeStrings.storageCleared(ByteCountFormatter.string(fromByteCount: freed, countStyle: .file))
    }

    private func replace(_ old: AccountSession, with fresh: AccountSession) {
        guard let index = sessions.firstIndex(where: { $0 === old }) else { return }
        NotificationActions.shared.detach(userId: old.userId)
        watchAuthErrors(fresh)
        NotificationActions.shared.attach(fresh)
        PushRegistration.shared.add(fresh)
        // Spec account-start deviceKey: a recovery from needsPin gives a new session.
        Task { await DeviceKeyRegistration.run(fresh, api: api) }
        sessions[index] = fresh
        if attachedUserId == fresh.userId {
            attachedUserId = nil
            attachServices(fresh)
        }
    }

    /// The account's remembered name, else Você.
    static func name(of userId: String) -> String {
        let label = BipeAccountLabels.label(for: userId)
        return label == userId ? BipeStrings.tabYou : label
    }

    /// Restores the account's saved session; when there is none, signs in with the device credential. That password
    /// is there only between a sign-up or recovery and its device key registration (ADR 0283). `nil` when neither
    /// works now.
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
        guard let credential, let password = credential.password else {
            log.notice("no device credential for this account")
            return nil
        }
        do {
            return try await AccountSession.signIn(userId: credential.userId, password: password)
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
