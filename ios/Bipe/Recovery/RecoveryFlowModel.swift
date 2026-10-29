// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

extension BipeOnboardingRecoveryRow {
    /// The provider of a linked-account row (ADR 0096).
    var linkProvider: BipeLinkProvider? {
        switch self {
        case .apple: .apple
        case .google: .google
        default: nil
        }
    }
}

/// The way back into an existing account (ADR 0057, ADR 0059): recovery-start, recovery-pin, recovery-email and
/// recovery-waiting (bipe-shared product/screens). account-start shows it from onboarding-welcome ("Já tenho conta")
/// and while a kept recovery waits. Every path gives access only: message history needs encryption-status.
@MainActor
final class RecoveryFlowModel: ObservableObject {
    enum Route: Hashable {
        case pin
        case email
        case beta
        case waiting
        case oldPhone
        case linked
    }

    /// Spec recovery-pin, state factor.
    enum Factor: Equatable {
        case token
        case emailLink
        /// The proof of recovery-linked (ADR 0096).
        case linked
    }

    /// Spec recovery-linked, state phase.
    enum LinkedPhase: Equatable {
        case signingIn
        case failed
    }

    /// Spec recovery-pin, state phase. newPin and newPinConfirm are the signup-pin steps of forgotPin.
    enum PinPhase: Equatable {
        case editing
        case checking
        case wrong
        case blocked
        case failed
        case newPin
        case newPinConfirm
    }

    /// Spec recovery-email, state phase.
    enum EmailPhase: Equatable {
        case editing
        case sending
        case sent
        case failed
    }

    /// Spec recovery-waiting, state phase.
    enum WaitingPhase: Equatable {
        case waiting
        case claiming
        case ready
        case cancelled
        case expired
        case failed
    }

    @Published var path: [Route] = []

    // recovery-start
    @Published private(set) var rows: [BipeOnboardingRecoveryRow] = []
    @Published private(set) var hasSyncedToken = false

    // recovery-pin
    @Published private(set) var factor: Factor = .token
    @Published private(set) var pin = ""
    @Published private(set) var pinPhase: PinPhase = .editing
    @Published private(set) var triesLeft: Int?
    @Published private(set) var pinError: String?
    @Published private(set) var newPinProblem: BipePinProblem?
    @Published private(set) var newPinMismatch = false

    // recovery-email
    @Published private(set) var email = ""
    @Published private(set) var emailPhase: EmailPhase = .editing
    @Published private(set) var emailInvalid = false
    @Published private(set) var emailError: String?

    // beta sign-in (recovery-start action chooseBeta)
    @Published private(set) var betaEmail = ""
    @Published private(set) var betaPassword = ""
    @Published private(set) var betaBusy = false
    @Published private(set) var betaError: String?

    // recovery-linked (ADR 0096)
    @Published private(set) var linkProvider: BipeLinkProvider = .apple
    @Published private(set) var linkedPhase: LinkedPhase = .signingIn
    @Published private(set) var linkedError: String?
    /// The provider of the kept recovery, for recovery-waiting. `nil` for e-mail, and after the app restarts.
    @Published private(set) var waitingProvider: BipeLinkProvider?

    // recovery-waiting
    @Published private(set) var pending: BipeRecoveryStarted?
    @Published private(set) var waitingPhase: WaitingPhase = .waiting

    let api: BipeAPIClient
    /// Reads the app's own group and the fork's group; writes only the app's own group (ADR 0147).
    private let store = BipeCredentialStore()
    private let onSignedIn: (AccountSession) -> Void
    private var token: BipeCredentialStore.Item?
    private var linkToken: String?
    /// The provider proof of recovery-linked. In memory only; it works once.
    private var linkProof: BipeLinkProof?
    private var linkProviders: [BipeLinkProvider] = []
    private var providersTask: Task<Void, Never>?
    private var newPin = ""
    private var pollTask: Task<Void, Never>?

    /// Spec soft-logout needsPin: the account whose token the server refused.
    let softLogoutUserId: String?

    init(api: BipeAPIClient, softLogoutUserId: String? = nil, onSignedIn: @escaping (AccountSession) -> Void) {
        self.api = api
        self.softLogoutUserId = softLogoutUserId
        self.onSignedIn = onSignedIn
        pending = store.pendingRecovery()
        if pending != nil {
            path = [.waiting]
        }
    }

    deinit {
        pollTask?.cancel()
    }

    // MARK: - recovery-start

    /// Action appear: reads the synced store for a recovery token and sets the rows.
    func appear() {
        // Every account signed in on this phone (ADR 0086): adding an account never recovers one already here.
        let signedIn = Set(SessionStore.loadAll().keys)
        token = softLogoutUserId.flatMap(store.recoveryToken(for:)) ?? store.recoveryToken(excluding: signedIn)
        hasSyncedToken = token != nil
        // oldPhone ships in bipe-app (bipe-chat/bipe#608); the forks keep its planned:203 gate. A row gated "flag:developer"
        // shows only in developer builds. The e-mail and password row has no gate: App Review signs in through it (spec soft-logout notes).
        rows = BipeOnboardingRecoveryRow.allCases.filter { row in
            (row.gate == nil || row == .oldPhone || (row.gate == "flag:developer" && AppConfig.isDeveloperBuild))
                && (!row.needsSyncedToken || hasSyncedToken)
                && (row.linkProvider.map { linkProviders.contains($0) } ?? true)
        }
        // Spec recovery-start linkProviders: Apple through Sign in with Apple, Google through the web flow. The rows
        // show once the server answers; a server without them keeps them hidden (fail closed).
        if providersTask == nil {
            providersTask = Task { [weak self] in
                guard let self, let providers = try? await api.linkProviders() else { return }
                linkProviders = providers.usableOnIOS
                appear()
            }
        }
        // Spec soft-logout enterPin: straight to recovery-pin when the account's synced token is here.
        if softLogoutUserId != nil, hasSyncedToken, path.isEmpty {
            openPin(.token)
        }
    }

    /// Actions chooseToken, chooseOldPhone, chooseEmail and chooseBeta.
    func choose(_ row: BipeOnboardingRecoveryRow) {
        switch row {
        case .token:
            openPin(.token)
        case .email:
            emailPhase = .editing
            emailError = nil
            path.append(.email)
        case .beta:
            betaError = nil
            path.append(.beta)
        case .oldPhone:
            path.append(.oldPhone)
        case .apple, .google:
            if let provider = row.linkProvider {
                linkProvider = provider
                path.append(.linked)
                Task { await startLinked() }
            }
        }
    }

    // MARK: - recovery-linked

    /// Actions start and retry: the provider sign-in, then recovery-pin with factor linked.
    func startLinked() async {
        linkedPhase = .signingIn
        linkedError = nil
        do {
            switch try await LinkSignIn.run(linkProvider, api: api, accessToken: nil) {
            case .proof(let proof):
                linkProof = proof
                if path.last == .linked {
                    path.removeLast()
                }
                openPin(.linked)
            case .cancelled:
                path = []
            case .failed:
                linkedPhase = .failed
                linkedError = BipeStrings.recoveryLinkedInvalid
            }
        } catch {
            linkedPhase = .failed
            linkedError = error == .network ? BipeStrings.errorNetwork : error.localizedMessage
        }
    }

    // MARK: - recovery-pin

    private func openPin(_ factor: Factor) {
        self.factor = factor
        pin = ""
        pinPhase = .editing
        triesLeft = nil
        pinError = nil
        newPinProblem = nil
        newPinMismatch = false
        if path.last != .pin {
            path.append(.pin)
        }
    }

    /// Actions typeDigit and deleteDigit, through the system digit keyboard. At pinLength digits, submits.
    func editPin(_ text: String) {
        guard pinPhase != .checking, pinPhase != .blocked else { return }
        let digits = String(BipePIN.sanitize(text).prefix(BipeRegistries.pinLength))
        pin = digits
        if pinPhase == .wrong || pinPhase == .failed {
            pinPhase = .editing
            pinError = nil
        }
        newPinProblem = nil
        newPinMismatch = false
        guard digits.count == BipeRegistries.pinLength else { return }
        switch pinPhase {
        case .newPin:
            if let problem = BipePIN.problem(for: digits) {
                newPinProblem = problem
                pin = ""
            } else {
                newPin = digits
                pin = ""
                pinPhase = .newPinConfirm
            }
        case .newPinConfirm:
            if digits == newPin {
                Task { await submitNewPin() }
            } else {
                newPinMismatch = true
                newPin = ""
                pin = ""
                pinPhase = .newPin
            }
        default:
            Task { await submit() }
        }
    }

    /// Action submit.
    func submit() async {
        guard pin.count == BipeRegistries.pinLength else { return }
        pinPhase = .checking
        do {
            switch factor {
            case .token:
                guard let token else { throw BipeAPIError.server(.recoveryInvalid, reason: nil, statusCode: 403) }
                let credentials = try await api.recoverWithToken(token.secret, pin: pin)
                pin = ""
                try await signIn(credentials)
            case .emailLink:
                guard let linkToken else { throw BipeAPIError.server(.recoveryInvalid, reason: nil, statusCode: 403) }
                let started = try await api.confirmRecoveryEmail(linkToken: linkToken, pin: pin, newPIN: nil)
                pin = ""
                startWaiting(started)
            case .linked:
                guard let proof = linkProof else { throw BipeAPIError.server(.recoveryInvalid, reason: nil, statusCode: 403) }
                // The proof works once, whatever the answer.
                linkProof = nil
                let started = try await api.recoverWithLink(linkProvider, proof: proof, pin: pin)
                pin = ""
                waitingProvider = linkProvider
                startWaiting(started)
            }
        } catch let error as BipeAPIError {
            pin = ""
            handlePinError(error)
        } catch {
            pin = ""
            pinPhase = .failed
            pinError = BipeStrings.errorGeneric
        }
    }

    /// Action forgotPin: emailLink asks for a new PIN (a pin_reset recovery); token opens recovery-email,
    /// because the PIN resets only through the recovery e-mail.
    func forgotPin() {
        switch factor {
        case .emailLink:
            pin = ""
            newPin = ""
            pinError = nil
            pinPhase = .newPin
        case .token, .linked:
            // A linked account never resets the PIN (ADR 0096): recovery-email does.
            choose(.email)
        }
    }

    private func submitNewPin() async {
        guard let linkToken else { return }
        pinPhase = .checking
        do {
            let started = try await api.confirmRecoveryEmail(linkToken: linkToken, pin: nil, newPIN: newPin)
            newPin = ""
            pin = ""
            startWaiting(started)
        } catch let error as BipeAPIError {
            pin = ""
            if case .server(.pinWeak, let reason, _) = error {
                newPinProblem = reason.flatMap(BipePinProblem.init(rawValue:)) ?? .sequence
                newPin = ""
                pinPhase = .newPin
            } else {
                handlePinError(error)
            }
        } catch {
            pinPhase = .failed
            pinError = BipeStrings.errorGeneric
        }
    }

    private func handlePinError(_ error: BipeAPIError) {
        switch error {
        case .server(.pinWrong, _, _):
            triesLeft = error.pinTriesLeft
            pinPhase = .wrong
            pinError = BipeStrings.pinWrong(String(triesLeft ?? 0))
        case .server(.pinBlocked, _, _):
            pinPhase = .blocked
            pinError = factor == .linked ? BipeStrings.recoveryLinkedPinBlocked : nil
        case .server(.recoveryInvalid, _, _):
            pinPhase = .failed
            pinError = factor == .linked ? BipeStrings.recoveryLinkedInvalid : BipeStrings.errorRecoveryInvalid
            // Spec notes: an old or revoked synced token is deleted; recovery-start shows without the token row.
            if factor == .token, let token {
                store.deleteRecoveryToken(for: token.userID)
                self.token = nil
                appear()
            }
        case .server(.rateLimited, _, _):
            pinPhase = .failed
            pinError = BipeStrings.errorRateLimited
        case .network:
            pinPhase = .failed
            pinError = BipeStrings.errorNetwork
        default:
            pinPhase = .failed
            pinError = BipeStrings.errorGeneric
        }
    }

    /// From a recovery_invalid on the token path: back to recovery-start.
    func backToStart() {
        path = []
        appear()
    }

    // MARK: - recovery-email

    /// Action editEmail.
    func editEmail(_ text: String) {
        email = text
        emailInvalid = false
        if emailPhase == .failed {
            emailPhase = .editing
            emailError = nil
        }
    }

    /// Action send: checks the address locally, then POST /v1/recovery/email.
    func sendEmail() async {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isPlausibleEmail(address) else {
            emailInvalid = true
            return
        }
        emailPhase = .sending
        do {
            try await api.requestRecoveryEmail(address)
            emailPhase = .sent
        } catch {
            emailPhase = .failed
            switch error {
            case .server(.rateLimited, _, _): emailError = BipeStrings.errorRateLimited
            case .server(.emailUnavailable, _, _): emailError = BipeStrings.errorEmailUnavailable
            default: emailError = BipeStrings.errorNetwork
            }
        }
    }

    /// Action openLink: the recovery link from the e-mail, opened on this phone. A link without a token does nothing.
    func openLink(_ url: URL) {
        guard let token = BipeRecoveryLink.token(from: url) else { return }
        linkToken = token
        if path.last == .pin {
            path.removeLast()
        }
        openPin(.emailLink)
    }

    private static func isPlausibleEmail(_ address: String) -> Bool {
        let parts = address.split(separator: "@", omittingEmptySubsequences: false)
        guard parts.count == 2, !parts[0].isEmpty, parts[1].contains("."), !address.contains(" ") else { return false }
        return !parts[1].hasPrefix(".") && !parts[1].hasSuffix(".")
    }

    // MARK: - beta sign-in

    func editBetaEmail(_ text: String) {
        betaEmail = text
        betaError = nil
    }

    func editBetaPassword(_ text: String) {
        betaPassword = text
        betaError = nil
    }

    var canSubmitBeta: Bool {
        !betaBusy && !betaEmail.trimmingCharacters(in: .whitespaces).isEmpty && !betaPassword.isEmpty
    }

    /// Action chooseBeta: an e-mail goes through POST /v1/beta/login for the Matrix ID (bipe-chat/bipe#627); a localpart
    /// or a Matrix ID goes to Client.login as it is. Then the move to device-first (POST /v1/me/device-first). The
    /// password is never stored; the move gives the hidden password and the token. Every failure shows the same text.
    func submitBeta() async {
        guard canSubmitBeta else { return }
        betaBusy = true
        defer { betaBusy = false }
        let input = betaEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let user = Self.isBetaEmail(input) ? try await api.betaLogin(email: input, password: betaPassword) : input
            let session = try await AccountSession.signIn(userId: user, password: betaPassword)
            betaPassword = ""
            if let accessToken = try? session.client.session().accessToken,
               let credentials = try? await api.moveToDeviceFirst(accessToken: accessToken) {
                store.save(credentials)
            }
            onSignedIn(session)
        } catch {
            betaError = BipeStrings.errorGeneric
        }
    }

    /// An e-mail has an @ inside; a Matrix ID (@localpart:server) starts with it.
    static func isBetaEmail(_ input: String) -> Bool {
        !input.hasPrefix("@") && input.contains("@")
    }

    // MARK: - recovery-waiting

    private func startWaiting(_ started: BipeRecoveryStarted) {
        store.savePendingRecovery(started)
        pending = started
        waitingPhase = .waiting
        path = [.waiting]
        Task { await poll() }
    }

    /// Action appear: polls the claim on open, on foreground and at readyAt.
    func waitingAppear() async {
        await poll()
    }

    /// Action claim. Before readyAt the server answers waiting; after it, the credentials come once.
    func poll() async {
        guard let pending, waitingPhase == .waiting || waitingPhase == .failed else { return }
        waitingPhase = .claiming
        do {
            let claim = try await api.claimRecovery(id: pending.recoveryId, claimSecret: pending.claimSecret)
            switch claim.state {
            case .waiting:
                waitingPhase = .waiting
                schedulePoll(at: claim.readyAt ?? pending.readyAt)
            case .completed:
                if pending.purpose == .access, let credentials = claim.credentials {
                    try await signIn(credentials)
                    store.deletePendingRecovery()
                    waitingPhase = .ready
                } else {
                    store.deletePendingRecovery()
                    waitingPhase = .ready
                }
            case .cancelled:
                waitingPhase = .cancelled
            case .expired:
                waitingPhase = .expired
            }
        } catch BipeAPIError.server(.recoveryInvalid, _, _) {
            waitingPhase = .expired
        } catch {
            waitingPhase = .failed
        }
    }

    /// Action startOver: from cancelled or expired (and after a PIN reset), back to recovery-start.
    func startOver() {
        pollTask?.cancel()
        waitingProvider = nil
        store.deletePendingRecovery()
        pending = nil
        waitingPhase = .waiting
        path = []
        appear()
    }

    private func schedulePoll(at date: Date) {
        pollTask?.cancel()
        let delay = max(date.timeIntervalSinceNow, 0) + 2
        pollTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            await self?.poll()
        }
    }

    // MARK: - Sign-in

    /// recovery-old-phone signed in with the old phone's approval: chat-list, with the history already here.
    func signedInWithOldPhone(_ session: AccountSession) {
        onSignedIn(session)
    }

    /// Stores the password (this device only) and the token (synced), signs in, then opens chat-list.
    /// encryption-status then shows the ways to get the message history.
    private func signIn(_ credentials: BipeCredentials) async throws {
        store.save(credentials)
        let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
        onSignedIn(session)
    }
}
