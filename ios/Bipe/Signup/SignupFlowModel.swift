// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import UIKit

/// Type PendingInvite of spec onboarding-welcome.
struct PendingInvite: Equatable {
    var code: String
    var inviterHandle: String?
    var isValid: Bool
}

/// Type SignupStart of spec account-new: a sign-up from the Nova conta window, with the invite the account on screen made
/// (inviteId set), or with someone else's invite from a link (invite-signed-in, inviteId nil).
struct SignupStart: Equatable, Identifiable {
    var inviteId: String?
    var code: String
    var accountType: BipeAccountType
    /// Someone else's invite only: their handle, for "invited by" and signup-done.
    var inviterHandle: String?

    var id: String { inviteId ?? code }
    /// The account on screen made the invite: the sign-up has no inviter.
    var isOwnInvite: Bool { inviteId != nil }
}

/// The sign-up flow: onboarding-welcome, signup-invite-code, signup-waitlist, signup-name, signup-pin, signup-fallback
/// and signup-create (bipe-shared product/screens). The state and action names follow the specs.
@MainActor
final class SignupFlowModel: ObservableObject {
    /// The steps on top of onboarding-welcome. The system back button pops one step (action back).
    enum Route: Hashable {
        case inviteCode
        case name
        case pin
        case pinConfirm
        case fallback
        case create
    }

    @Published var path: [Route] = [] {
        didSet {
            // Action back of signup-pin: leaving the confirm step goes back to create and forgets firstPin.
            if pinStep == .confirm, !path.contains(.pinConfirm) {
                resetPin()
            }
        }
    }

    /// Opens a step. A step already on the path is not opened twice.
    private func go(_ route: Route) {
        if let index = path.firstIndex(of: route) {
            path.removeSubrange((index + 1)...)
        } else {
            path.append(route)
        }
    }

    // MARK: onboarding-welcome

    enum Variant: Equatable {
        case loading
        case invited
        case inviteOnly
        case open
    }

    enum PasteResult: Equatable {
        case none
        case notAnInvite
    }

    @Published private(set) var variant: Variant = .loading
    @Published private(set) var invite: PendingInvite?
    @Published private(set) var inviteRequired = true
    @Published private(set) var termsVersion: String?
    @Published private(set) var clipboardHasText = false
    @Published private(set) var pasteResult: PasteResult = .none

    // MARK: signup-invite-code

    enum InviteCodePhase: Equatable {
        case editing
        case checking
        case invalid
    }

    @Published private(set) var code = ""
    @Published private(set) var inviteCodePhase: InviteCodePhase = .editing
    /// The error text under the field.
    @Published private(set) var inviteCodeError: String?

    // MARK: signup-waitlist

    enum WaitlistPhase: Equatable {
        case editing
        case sending
        case done
        case failed
    }

    @Published var showsWaitlist = false
    @Published private(set) var waitlistEmail = ""
    @Published private(set) var waitlistPhase: WaitlistPhase = .editing
    @Published private(set) var waitlistEmailInvalid = false
    @Published private(set) var waitlistError: String?

    // MARK: signup-name

    @Published private(set) var displayName = ""
    @Published private(set) var termsAccepted = false
    @Published private(set) var showTermsRequired = false

    // MARK: signup-pin

    enum PinStep: Equatable {
        case create
        case confirm
    }

    enum PinProblem: Equatable {
        case none
        case repeatedDigit
        case sequence
        case date
        case mismatch
    }

    @Published private(set) var pinStep: PinStep = .create
    @Published private(set) var pin = ""
    private var firstPin = ""
    @Published private(set) var pinProblem: PinProblem = .none

    // MARK: signup-fallback

    enum FallbackPhase: Equatable {
        case choose
        case email
    }

    @Published private(set) var fallbackPhase: FallbackPhase = .choose
    @Published private(set) var fallbackEmail = ""
    @Published private(set) var fallbackEmailInvalid = false
    /// Spec signup-fallback linkProvider: Apple on iOS when the server takes Sign in with Apple (ADR 0096).
    @Published private(set) var fallbackLinkProvider: BipeLinkProvider?
    /// Spec signup-fallback linkChosen: the link runs right after the account exists.
    private var linkChosen = false

    // MARK: signup-create

    enum CreatePhase: Equatable {
        case attesting
        case creating
        case signingIn
        case failed
        case done
    }

    enum CreateError: Equatable {
        case none
        case network
        case rateLimited
        case inviteInvalid
        case termsRequired
        case pinWeak(BipePinProblem)
        case emailTaken
        case attestationInvalid
        case attestationFailed
        case signInFailed
        case generic
    }

    @Published private(set) var createPhase: CreatePhase = .attesting
    @Published private(set) var createError: CreateError = .none

    /// The form of signup-create (type SignupForm). The PIN stays in memory only.
    private var formPin = ""
    private var formEmail: String?
    /// Credentials that are stored but not signed in yet: a retry signs in again without a new request.
    private var storedCredentials: BipeCredentials?

    private let api: BipeAPIClient
    private let credentialStore = BipeCredentialStore()
    private let onSignedUp: (AccountSession, SignupOutcome) -> Void
    /// Action haveAccount of onboarding-welcome: account-start opens recovery-start.
    let onHaveAccount: () -> Void
    /// Set for a sign-up from account-new: it starts at signup-name with the invite, and has no inviter.
    let start: SignupStart?
    /// true once signup-create ran: a cancel no longer revokes the invite of ``start`` (spec account-new cancelSignup).
    private(set) var reachedCreate = false

    init(api: BipeAPIClient,
         start: SignupStart? = nil,
         onSignedUp: @escaping (AccountSession, SignupOutcome) -> Void,
         onHaveAccount: @escaping () -> Void = {}) {
        self.api = api
        self.start = start
        self.onSignedUp = onSignedUp
        self.onHaveAccount = onHaveAccount
        if let start {
            // The account on screen made the invite: no inviter handle, so signup-name shows no "invited by".
            invite = PendingInvite(code: start.code, inviterHandle: start.isOwnInvite ? nil : start.inviterHandle, isValid: true)
            code = start.code
        }
    }

    /// A company sign-up from account-new: signup-name asks for the company name.
    var isCompany: Bool { start?.accountType == .company }

    // MARK: - onboarding-welcome actions

    /// Action appear: reads the sign-up mode (at most 3 s, then inviteOnly) and previews a pending invite.
    func appear() async {
        clipboardHasText = UIPasteboard.general.hasStrings
        let config = await withTaskGroup(of: BipeSignupConfig?.self) { group in
            group.addTask { [api] in try? await api.signupConfig() }
            group.addTask {
                try? await Task.sleep(for: .seconds(3))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        inviteRequired = config?.inviteRequired ?? true
        termsVersion = config?.termsVersion
        updateVariant()
    }

    /// Action openInviteLink: a bipe.chat/convite/<code> link the app was opened with.
    func openInviteLink(_ url: URL) async {
        guard let code = BipeInviteLink.code(from: url) else { return }
        await preview(code)
    }

    /// Action pasteInvite: reads the clipboard once, after a tap.
    func pasteInvite() async {
        guard let text = UIPasteboard.general.string, let code = BipeInviteLink.normalizedCode(text) else {
            pasteResult = .notAnInvite
            return
        }
        pasteResult = .none
        await preview(code)
    }

    /// Action primary: invited and open go to signup-name, inviteOnly to signup-invite-code. A pending invite is
    /// checked again first (bipe-chat/bipe#460): one that stopped being valid goes to signup-invite-code.
    func primary() async {
        switch variant {
        case .invited, .open:
            checkingInvite = true
            defer { checkingInvite = false }
            if let pending = invite, pending.isValid, let preview = try? await api.invitePreview(code: pending.code), !preview.valid {
                invite = nil
                code = pending.code
                inviteCodePhase = .invalid
                inviteCodeError = BipeStrings.inviteNoLongerValid
                updateVariant()
                go(.inviteCode)
                return
            }
            go(.name)
        case .inviteOnly: go(.inviteCode)
        case .loading: break
        }
    }

    /// true while primary checks the pending invite again.
    @Published private(set) var checkingInvite = false

    var startVariant: BipeOnboardingStart {
        BipeOnboardingStart.variant(invitePending: hasUsableInvite, inviteRequired: inviteRequired)
    }

    private var hasUsableInvite: Bool {
        invite?.isValid == true && invite?.inviterHandle != nil
    }

    private func preview(_ code: String) async {
        let preview = try? await api.invitePreview(code: code)
        invite = PendingInvite(code: code, inviterHandle: preview?.inviterHandle, isValid: preview?.valid ?? false)
        if invite?.isValid == true {
            self.code = code
        }
        updateVariant()
    }

    private func updateVariant() {
        switch startVariant {
        case .invited: variant = .invited
        case .inviteOnly: variant = .inviteOnly
        case .open: variant = .open
        }
    }

    // MARK: - signup-invite-code actions

    /// Action editCode.
    func editCode(_ text: String) {
        code = text
        inviteCodeError = nil
        inviteCodePhase = .editing
    }

    /// Action submit: takes the code out of the text and asks the server.
    func submitCode() async {
        guard !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard let normalized = BipeInviteLink.normalizedCode(code) else {
            inviteCodePhase = .invalid
            inviteCodeError = BipeStrings.errorInviteInvalid
            return
        }
        inviteCodePhase = .checking
        do {
            let preview = try await api.invitePreview(code: normalized)
            if preview.valid {
                invite = PendingInvite(code: normalized, inviterHandle: preview.inviterHandle, isValid: true)
                inviteCodePhase = .editing
                go(.name)
            } else {
                inviteCodePhase = .invalid
                inviteCodeError = BipeStrings.errorInviteInvalid
            }
        } catch {
            inviteCodePhase = .editing
            inviteCodeError = Self.isRateLimited(error) ? BipeStrings.errorRateLimited : BipeStrings.errorNetwork
        }
    }

    /// Action noInvite: opens signup-waitlist as a sheet.
    func noInvite() {
        showsWaitlist = true
    }

    // MARK: - signup-waitlist actions

    /// Action editEmail of signup-waitlist.
    func editWaitlistEmail(_ text: String) {
        waitlistEmail = text
        waitlistEmailInvalid = false
    }

    /// Action join.
    func joinWaitlist() async {
        let email = waitlistEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard BipeSignupRules.isPlausibleEmail(email) else {
            waitlistEmailInvalid = true
            return
        }
        waitlistPhase = .sending
        waitlistError = nil
        do {
            try await api.joinWaitlist(email: email, lang: BipeAPIClient.emailLanguage)
            waitlistPhase = .done
        } catch {
            waitlistPhase = .failed
            waitlistError = Self.isRateLimited(error) ? BipeStrings.errorRateLimited : BipeStrings.errorNetwork
        }
    }

    /// Action close: back to signup-invite-code.
    func closeWaitlist() {
        showsWaitlist = false
    }

    // MARK: - signup-name actions

    /// Action editName.
    func editName(_ text: String) {
        displayName = String(text.prefix(BipeSignupRules.displayNameMaxLength))
    }

    /// Action toggleTerms.
    func toggleTerms() {
        termsAccepted.toggle()
        if termsAccepted {
            showTermsRequired = false
        }
    }

    /// The legal version the checkbox accepts.
    var acceptedTermsVersion: String {
        termsVersion ?? BipeRegistries.legalVersion
    }

    var canContinueName: Bool {
        !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && termsAccepted
    }

    /// Action continue of signup-name.
    func continueFromName() {
        guard canContinueName else { return }
        resetPin()
        go(.pin)
    }

    // MARK: - signup-pin actions

    /// Actions typeDigit and deleteDigit: the field text of the system digit keyboard, digits only (paste is allowed).
    func editPin(_ text: String) {
        let digits = BipePIN.sanitize(text)
        if digits.count != pin.count {
            pinProblem = .none
        }
        pin = digits
        guard pin.count == BipeRegistries.pinLength else { return }
        switch pinStep {
        case .create:
            if let problem = BipePIN.problem(for: pin) {
                pinProblem = Self.pinProblem(problem)
                pin = ""
            } else {
                firstPin = pin
                pin = ""
                pinStep = .confirm
                go(.pinConfirm)
            }
        case .confirm:
            if pin == firstPin {
                formPin = pin
                pin = ""
                go(.fallback)
            } else {
                pinProblem = .mismatch
                resetPin(keepProblem: true)
                go(.pin)
            }
        }
    }

    private func resetPin(keepProblem: Bool = false) {
        pinStep = .create
        pin = ""
        firstPin = ""
        if !keepProblem {
            pinProblem = .none
        }
    }

    private static func pinProblem(_ problem: BipePinProblem) -> PinProblem {
        switch problem {
        case .repeatedDigit: .repeatedDigit
        case .date: .date
        case .sequence, .notDigits, .wrongLength: .sequence
        }
    }

    // MARK: - signup-fallback actions

    /// Action addEmail.
    func addEmail() {
        fallbackPhase = .email
    }

    /// Action editEmail of signup-fallback.
    func editFallbackEmail(_ text: String) {
        fallbackEmail = text
        fallbackEmailInvalid = false
    }

    /// Action submitEmail.
    func submitEmail() async {
        let email = fallbackEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard BipeSignupRules.isPlausibleEmail(email) else {
            fallbackEmailInvalid = true
            return
        }
        formEmail = email
        await create()
    }

    /// Action skip.
    func skipEmail() async {
        formEmail = nil
        linkChosen = false
        await create()
    }

    /// Reads which provider signup-fallback can offer. A server without it hides the button (fail closed).
    func loadFallbackLink() async {
        let providers = (try? await api.linkProviders().usableOnIOS) ?? []
        fallbackLinkProvider = providers.contains(.apple) ? .apple : nil
    }

    /// Action linkAccount: makes the account without an e-mail, then runs the link (spec signup-fallback).
    func linkAccount() async {
        formEmail = nil
        linkChosen = fallbackLinkProvider != nil
        await create()
    }

    // MARK: - signup-create actions

    /// Action start of signup-create. Spec notes: retry key, attestation, storage, then sign-in.
    func create() async {
        go(.create)
        reachedCreate = true
        createError = .none
        if let credentials = storedCredentials {
            await signIn(credentials)
            return
        }
        let pending = credentialStore.pendingSignup(pin: formPin)
        let accountPin = pending.pin ?? formPin
        createPhase = .attesting
        let attestation: BipeAttestation?
        if BipeAppAttest.isSupported {
            do {
                attestation = try await BipeAppAttest.attestationUnlessServiceDown(api: api)
            } catch {
                fail(error)
                return
            }
        } else {
            attestation = nil
        }
        createPhase = .creating
        let request = BipeSignupRequest(inviteCode: invite?.isValid == true ? invite?.code : nil,
                                        email: formEmail,
                                        pin: accountPin,
                                        handle: nil,
                                        displayName: displayName.trimmingCharacters(in: .whitespacesAndNewlines),
                                        attestation: attestation,
                                        signupKey: pending.key,
                                        termsVersion: termsAccepted ? acceptedTermsVersion : nil,
                                        accountType: isCompany ? .company : nil)
        let response: BipeSignupResponse
        do {
            response = try await signupRetryingLostAnswers(request)
        } catch {
            if case .server(let code, _, _) = error, code != .rateLimited, code != .internal {
                // A field error: the server made nothing, so the next try takes the PIN from the form again.
                credentialStore.updatePendingSignupPIN(nil)
            }
            fail(error)
            return
        }
        guard let credentials = response.credentials else {
            fail(.unexpectedResponse(statusCode: 201))
            return
        }
        // Keep the pending sign-up while the password cannot be read back: it is the only other copy.
        if credentialStore.save(credentials) {
            credentialStore.deletePendingSignup()
        }
        storedCredentials = credentials
        inviterUserId = response.inviterUserId
        await signIn(credentials)
    }

    /// Action retry of signup-create.
    func retryCreate() async {
        await create()
    }

    /// Action fix of signup-create: back to the step that owns the field.
    func fix() {
        switch createError {
        case .inviteInvalid:
            invite = nil
            path = [.inviteCode]
        case .termsRequired:
            termsAccepted = false
            showTermsRequired = true
            path = start != nil ? [] : Array(path.prefix { $0 != .name }) + [.name]
        case .pinWeak:
            resetPin()
            path = Array(path.prefix { $0 != .pin }) + [.pin]
        case .emailTaken:
            fallbackPhase = .email
            path.removeAll { $0 == .create }
        default:
            break
        }
    }

    var canFix: Bool {
        switch createError {
        case .inviteInvalid, .termsRequired, .pinWeak, .emailTaken: true
        default: false
        }
    }

    private var inviterUserId: String?

    private func signIn(_ credentials: BipeCredentials) async {
        createPhase = .signingIn
        do {
            let session = try await AccountSession.signIn(userId: credentials.userId, password: credentials.password)
            // Spec signup-fallback linkAccount: no owner check, the person makes the account now. A failed or cancelled
            // link does not stop the sign-up; linked-accounts in settings offers it again.
            if linkChosen, let provider = fallbackLinkProvider {
                linkChosen = false
                _ = await LinkedAccountsModel.runLink(provider, api: api) { session.accessToken() }
            }
            createPhase = .done
            formPin = ""
            // A sign-up from account-new has no inviter chat and no greeting: the inviter is an account on this phone.
            let outcome = start?.isOwnInvite == true ? SignupOutcome()
                : SignupOutcome(inviterUserId: inviterUserId, inviterHandle: invite?.inviterHandle)
            onSignedUp(session, outcome)
        } catch {
            createPhase = .failed
            createError = .signInFailed
        }
    }

    /// Mobile data drops often. A try without an answer may still have made the account, and the same key
    /// returns it, so a few quiet retries are safe (ADR 0094). 429 signup_in_progress waits and sends again.
    private func signupRetryingLostAnswers(_ request: BipeSignupRequest) async throws(BipeAPIError) -> BipeSignupResponse {
        var attempt = 0
        while true {
            attempt += 1
            do {
                return try await api.signup(request)
            } catch {
                let retryable: Bool
                switch error {
                case .network, .unexpectedResponse: retryable = true
                case .server(.rateLimited, let reason, _): retryable = reason == "signup_in_progress"
                case .server(.internal, _, _): retryable = true
                default: retryable = false
                }
                guard retryable, attempt < 4 else { throw error }
                try? await Task.sleep(for: .seconds(2 * attempt))
            }
        }
    }

    private func fail(_ error: BipeAPIError) {
        createPhase = .failed
        createError = switch error {
        case .network: .network
        case .server(.rateLimited, _, _): .rateLimited
        case .server(.inviteInvalid, _, _): .inviteInvalid
        case .server(.termsRequired, _, _): .termsRequired
        case .server(.pinWeak, let reason, _): .pinWeak(reason.flatMap(BipePinProblem.init(rawValue:)) ?? .sequence)
        case .server(.emailTaken, _, _): .emailTaken
        case .server(.attestationInvalid, _, _): .attestationInvalid
        case .attestationFailed, .attestationServiceDown: .attestationFailed
        default: .generic
        }
    }

    /// The text of the error (spec signup-create notes: error map).
    var createErrorText: String {
        switch createError {
        case .none: ""
        case .network: BipeStrings.errorNetwork
        case .rateLimited: BipeStrings.errorRateLimited
        case .inviteInvalid: BipeStrings.errorInviteInvalid
        case .termsRequired: BipeStrings.signupTermsRequired
        case .pinWeak(let problem): problem.localizedMessage
        case .emailTaken: BipeStrings.errorEmailTaken
        case .attestationInvalid: BipeStrings.errorAttestationInvalid
        case .attestationFailed: BipeStrings.errorAttestationFailed
        case .signInFailed: BipeStrings.signupSigninFailed
        case .generic: BipeStrings.errorGeneric
        }
    }

    private static func isRateLimited(_ error: BipeAPIError) -> Bool {
        if case .server(.rateLimited, _, _) = error { return true }
        return false
    }
}
