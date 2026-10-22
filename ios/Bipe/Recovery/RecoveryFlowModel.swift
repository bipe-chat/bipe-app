// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// The way back into an existing account: recovery-start, recovery-pin, recovery-email and the beta sign-in
/// (bipe-shared product/screens). State and action names follow the specs.
@MainActor
final class RecoveryFlowModel: ObservableObject {
    enum Route: Hashable {
        case pin
        case email
        case newPin
        case newPinConfirm
        case beta
    }

    @Published var path: [Route] = []

    // MARK: recovery-start

    @Published private(set) var hasSyncedToken = false
    private var syncedToken: BipeCredentialStore.Item?

    var rows: [BipeOnboardingRecoveryRow] {
        BipeOnboardingRecoveryRow.allCases.filter { row in
            // A gated row stays hidden until its issue ships (oldPhone: bipe-chat/bipe#203).
            row.gate == nil && (!row.needsSyncedToken || hasSyncedToken)
        }
    }

    // MARK: recovery-pin

    enum Factor: Equatable {
        case token
        case emailLink(String)
    }

    enum PinPhase: Equatable {
        case editing
        case checking
        case wrong
        case blocked
        case failed
    }

    @Published private(set) var factor: Factor = .token
    @Published private(set) var pin = ""
    @Published private(set) var pinPhase: PinPhase = .editing
    @Published private(set) var triesLeft = 0
    @Published private(set) var pinError: String?

    // New PIN after "Esqueci minha senha" on the e-mail path (the signup-pin steps).
    @Published private(set) var newPin = ""
    private var firstNewPin = ""
    @Published private(set) var newPinProblem: String?

    // MARK: recovery-email

    enum EmailPhase: Equatable {
        case editing
        case sending
        case sent
        case failed
    }

    @Published private(set) var email = ""
    @Published private(set) var emailPhase: EmailPhase = .editing
    @Published private(set) var emailInvalid = false
    @Published private(set) var emailError: String?

    // MARK: beta sign-in

    @Published private(set) var betaEmail = ""
    @Published private(set) var betaPassword = ""
    @Published private(set) var betaBusy = false
    @Published private(set) var betaError: String?

    private let api: BipeAPIClient
    private let store = BipeCredentialStore()
    private let onSignedIn: (AccountSession) -> Void
    private let onWaiting: () -> Void

    init(api: BipeAPIClient, onSignedIn: @escaping (AccountSession) -> Void, onWaiting: @escaping () -> Void) {
        self.api = api
        self.onSignedIn = onSignedIn
        self.onWaiting = onWaiting
    }

    /// Action appear of recovery-start: a synced token of an account that is not on this phone.
    func appear() {
        let signedIn = Set([SessionStore.load()?.userId].compactMap { $0 })
        syncedToken = store.recoveryToken(excluding: signedIn)
        hasSyncedToken = syncedToken != nil
    }

    /// Action chooseToken.
    func chooseToken() {
        factor = .token
        resetPin()
        path = [.pin]
    }

    /// Action chooseEmail.
    func chooseEmail() {
        path = [.email]
    }

    /// Action chooseBeta.
    func chooseBeta() {
        path = [.beta]
    }

    /// Action openLink of recovery-email: the link from the recovery e-mail, opened on this phone.
    func openLink(_ url: URL) {
        guard let token = BipeRecoveryLink.token(from: url) else { return }
        factor = .emailLink(token)
        resetPin()
        path = [.pin]
    }

    // MARK: - recovery-pin actions

    /// Actions typeDigit and deleteDigit. At pinLength digits, submits.
    func editPin(_ text: String) async {
        pin = BipePIN.sanitize(text)
        if pinPhase == .wrong || pinPhase == .failed {
            pinPhase = .editing
        }
        guard pin.count == BipeRegistries.pinLength else { return }
        await submit(pin: pin, newPin: nil)
    }

    /// Action forgotPin: the e-mail path sets a new PIN; the token path goes to the recovery e-mail.
    func forgotPin() {
        switch factor {
        case .emailLink:
            newPin = ""
            firstNewPin = ""
            newPinProblem = nil
            path.append(.newPin)
        case .token:
            path = [.email]
        }
    }

    /// The new PIN of a PIN reset (bipe_recovery_new_pin_title), checked like signup-pin.
    func editNewPin(_ text: String, confirm: Bool) async {
        newPin = BipePIN.sanitize(text)
        newPinProblem = nil
        guard newPin.count == BipeRegistries.pinLength else { return }
        if !confirm {
            if let problem = BipePIN.problem(for: newPin) {
                newPinProblem = problem.localizedMessage
                newPin = ""
            } else {
                firstNewPin = newPin
                newPin = ""
                path.append(.newPinConfirm)
            }
        } else if newPin == firstNewPin {
            let chosen = newPin
            newPin = ""
            await submit(pin: nil, newPin: chosen)
        } else {
            newPinProblem = BipeStrings.pinMismatch
            newPin = ""
            firstNewPin = ""
            path.removeAll { $0 == .newPinConfirm }
        }
    }

    private func submit(pin: String?, newPin: String?) async {
        pinPhase = .checking
        pinError = nil
        switch factor {
        case .token:
            guard let token = syncedToken, let pin else { return }
            do {
                let credentials = try await api.recoverWithToken(token.secret, pin: pin)
                // The server replaced the token. A token stored under another key is removed.
                if token.userID != credentials.userId {
                    store.deleteRecoveryToken(for: token.userID)
                }
                _ = store.save(credentials)
                let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
                onSignedIn(session)
            } catch let error as BipeAPIError {
                if case .server(.recoveryInvalid, _, _) = error {
                    // Old or revoked: delete it and go back to recovery-start without the token row.
                    store.deleteRecoveryToken(for: token.userID)
                    appear()
                    path = []
                }
                failPin(error)
            } catch {
                pinPhase = .failed
                pinError = BipeStrings.errorGeneric
            }
        case .emailLink(let linkToken):
            do {
                let started = try await api.confirmRecoveryEmail(linkToken: linkToken, pin: pin, newPIN: newPin)
                store.savePendingRecovery(started)
                onWaiting()
            } catch {
                failPin(error)
            }
        }
        self.pin = ""
    }

    private func failPin(_ error: BipeAPIError) {
        switch error {
        case .server(.pinWrong, _, _):
            pinPhase = .wrong
            triesLeft = error.pinTriesLeft ?? 0
        case .server(.pinBlocked, _, _):
            pinPhase = .blocked
        case .network:
            pinPhase = .failed
            pinError = BipeStrings.errorNetwork
        case .server(.rateLimited, _, _):
            pinPhase = .failed
            pinError = BipeStrings.errorRateLimited
        case .server(.recoveryInvalid, _, _):
            pinPhase = .failed
            pinError = BipeStrings.errorRecoveryInvalid
        default:
            pinPhase = .failed
            pinError = BipeStrings.errorGeneric
        }
    }

    private func resetPin() {
        pin = ""
        pinPhase = .editing
        pinError = nil
    }

    // MARK: - recovery-email actions

    /// Action editEmail.
    func editEmail(_ text: String) {
        email = text
        emailInvalid = false
    }

    /// Action send. The answer is 202 also for an unknown address.
    func send() async {
        let address = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard BipeSignupRules.isPlausibleEmail(address) else {
            emailInvalid = true
            return
        }
        emailPhase = .sending
        emailError = nil
        do {
            try await api.requestRecoveryEmail(address)
            emailPhase = .sent
        } catch {
            emailPhase = .failed
            emailError = switch error {
            case .server(.rateLimited, _, _): BipeStrings.errorRateLimited
            case .server(.emailUnavailable, _, _): BipeStrings.errorEmailUnavailable
            default: BipeStrings.errorNetwork
            }
        }
    }

    // MARK: - beta sign-in (recovery-start, action chooseBeta)

    func editBetaEmail(_ text: String) {
        betaEmail = text
        betaError = nil
    }

    func editBetaPassword(_ text: String) {
        betaPassword = text
        betaError = nil
    }

    /// Client.login with the e-mail as user, then POST /v1/me/device-first. An account without a PIN stays a beta
    /// account until it sets one.
    func signInBeta() async {
        let user = betaEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !user.isEmpty, !betaPassword.isEmpty else { return }
        betaBusy = true
        defer { betaBusy = false }
        do {
            let session = try await AccountSession.signIn(userId: user, password: betaPassword)
            betaPassword = ""
            if let token = session.accessToken(), let credentials = try? await api.moveToDeviceFirst(accessToken: token) {
                _ = store.save(credentials)
            }
            onSignedIn(session)
        } catch {
            betaError = BipeStrings.errorGeneric
        }
    }
}
