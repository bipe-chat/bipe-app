// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Foundation
import MatrixRustSDK
import os

/// Calls back on SDK threads; the model reads the updates in order on the main actor.
final class GrantProgressStream: GrantQrLoginProgressListener {
    let updates: AsyncStream<GrantQrLoginProgress>
    private let continuation: AsyncStream<GrantQrLoginProgress>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(state: GrantQrLoginProgress) {
        continuation.yield(state)
    }

    func finish() {
        continuation.finish()
    }
}

/// The same for the code this phone shows (spec link-phone-show).
final class GrantGeneratedProgressStream: GrantGeneratedQrLoginProgressListener {
    let updates: AsyncStream<GrantGeneratedQrLoginProgress>
    private let continuation: AsyncStream<GrantGeneratedQrLoginProgress>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(state: GrantGeneratedQrLoginProgress) {
        continuation.yield(state)
    }

    func finish() {
        continuation.finish()
    }
}

/// Specs link-computer-scan, link-computer-warning and link-computer-approve (bipe-shared product/screens):
/// the phone scans the QR code of the computer, opens the MSC4108 secure channel, warns, waits through the
/// cooldown, checks the owner and approves on a native consent screen; MASDeviceApproval posts the MAS forms
/// with no web view (ADR 0116, ADR 0118, ADR 0224).
///
/// Mode phone is specs link-phone-show and link-phone-approve: this old phone shows the code, the new phone scans it
/// (ADR 0059), and the same warning, owner check and MAS page approve it.
@MainActor
final class LinkComputerModel: ObservableObject {
    enum Mode: Equatable {
        case computer
        case phone
    }

    /// Type LinkComputerSupport.
    enum Support: Equatable {
        case checking
        case supported
        case needsPin
        case notSupported
    }

    enum Step: Equatable {
        case scan
        /// Mode phone: link-phone-show.
        case show
        case warning
        case approve
    }

    enum ScanPhase: Equatable {
        case cameraDenied
        case scanning
        case connecting
        case showCheckCode
        case failed
    }

    /// Spec link-phone-show, state phase.
    enum ShowPhase: Equatable {
        case preparing
        case showingCode
        case enterCheckCode
        case checking
        case failed
    }

    enum ApprovePhase: Equatable {
        case confirmIdentity
        case loading
        case consent
        case finishing
        case done
        case failed
    }

    /// The failed phases, each with its string.
    enum Failure: Equatable {
        case notBipeCode
        case expired
        case insecure
        /// MissingSecretsBackup with a cross-signing cause: this phone lacks a private cross-signing key.
        case needsApproval
        case wrongCheckCode
        case stopped
        case network
        case signIn
        case generic

        func message(_ mode: Mode) -> String {
            switch (self, mode) {
            case (.notBipeCode, _): BipeStrings.appLinkComputerNotBipeCode
            case (.expired, .computer): BipeStrings.appLinkComputerExpired
            case (.expired, .phone): BipeStrings.appLinkPhoneExpired
            case (.insecure, .computer): BipeStrings.appLinkComputerInsecure
            case (.insecure, .phone): BipeStrings.appLinkPhoneInsecure
            case (.needsApproval, .computer): BipeStrings.appLinkComputerNeedsApproval
            case (.needsApproval, .phone): BipeStrings.appLinkPhoneNeedsApproval
            case (.wrongCheckCode, _): BipeStrings.appLinkPhoneWrongCheckCode
            case (.stopped, _): BipeStrings.appLinkComputerStopped
            case (.network, _): BipeStrings.errorNetwork
            case (.signIn, _): BipeStrings.linkComputerErrorSignIn
            case (.generic, _): BipeStrings.errorGeneric
            }
        }
    }

    /// State errorCode: the cause of a MissingSecretsBackup. The SDK sends it as text only
    /// (SecretsBundleExportError, matrix-sdk-crypto export_secrets_bundle).
    static func secretsCause(_ detail: String) -> (Failure, String) {
        let text = detail.lowercased()
        if text.contains("cross-signing") { return (.needsApproval, "keys-missing") }
        if text.contains("backup version") { return (.generic, "backup-version") }
        return (.generic, "no-crypto")
    }

    private static let log = Logger(subsystem: "chat.bipe.app", category: "link-device")

    /// Debug builds and the Simulator only: the copy button of link-phone-show (bipe-chat/bipe#608).
    static var canCopyCode: Bool {
        #if targetEnvironment(simulator) || DEBUG
        true
        #else
        false
        #endif
    }

    let mode: Mode
    /// Mode phone, spec company-team-add: the person this phone adds, with the name of the work account.
    struct PersonToAdd: Equatable {
        let seatId: String
        let name: String
        let accountName: String
    }
    let person: PersonToAdd?
    /// The grant finished: the person's phone has its session and can claim the seat.
    private let onGrantDone: (() -> Void)?
    /// Mode computer on a work account with more than one person: who the web session belongs to.
    @Published private(set) var people: [BipeSeat] = []
    @Published var pickedSeatId: String?

    @Published private(set) var support: Support = .checking
    @Published private(set) var step: Step = .scan
    @Published private(set) var scanPhase: ScanPhase = .scanning
    @Published private(set) var checkCode: String?
    @Published private(set) var scanError: Failure?
    @Published private(set) var secondsLeft = BipeRegistries.webLinkCooldownSeconds
    @Published private(set) var showPhase: ShowPhase = .preparing
    /// QrCodeData.toBytes of qrReady.
    @Published private(set) var qrBytes: Data?
    @Published private(set) var typedCheckCode = ""
    @Published private(set) var showError: Failure?
    /// When the new phone scanned the code (link-phone-approve requestedAt).
    @Published private(set) var requestedAt: Date?
    @Published private(set) var approvePhase: ApprovePhase = .confirmIdentity
    @Published private(set) var place: String?
    @Published private(set) var placeKnown = false
    /// The device card text and the IP of the MAS consent page.
    @Published private(set) var device: String?
    @Published private(set) var ip: String?
    @Published private(set) var approveError: Failure?
    /// State errorCode: a short code for support under the error. Never a key or a secret.
    @Published private(set) var errorCode: String?
    /// The flow ended: the view closes.
    @Published private(set) var isClosed = false
    /// Action approvePhone closed the flow: the view opens the encryption-status sheet after it closes.
    private(set) var opensApproval = false
    /// The Bipe PIN sheet of the owner check, for a phone with no biometrics (bipe-chat/bipe#785).
    let pinPrompt = AccountPinPrompt()

    let settings: SettingsModel
    var session: AccountSession { settings.session }
    /// The MAS page of the grant, and the only host the approval loads.
    private(set) var verificationURL: URL?
    private(set) var masHost: String?
    private var approval: MASDeviceApproval?
    private var consent: MASDeviceApproval.Consent?
    private var continuationSender: ContinuationMessageSender?
    private var checkCodeSender: CheckCodeSender?
    /// Mode phone: each code has its own number, so that a dropped code never touches the next one.
    private var codeAttempt = 0
    private var grantTask: Task<Void, Never>?
    private var countdown: Task<Void, Never>?
    private var startedAt = Date.now
    /// Approve posted the MAS consent form: close then confirms, and a cancel from the SDK is the decline.
    private var approved = false
    private var rejected = false
    private var finished = false
    /// Held only between the owner check and the one post of the MAS login form.
    private var password: String?

    init(settings: SettingsModel, mode: Mode = .computer, person: PersonToAdd? = nil, onGrantDone: (() -> Void)? = nil) {
        self.settings = settings
        self.mode = mode
        self.person = person
        self.onGrantDone = onGrantDone
        step = mode == .computer ? .scan : .show
    }

    private var matrix: BipeMatrixAccountAPI? {
        URL(string: session.client.homeserver()).map { BipeMatrixAccountAPI(homeserver: $0) }
    }

    /// The hidden password of the account on this phone (ADR 0057). Read only after the owner check.
    private var hiddenPassword: String? {
        BipeCredentialStore().password(for: session.userId)?.secret
    }

    var canContinue: Bool { secondsLeft == 0 }

    // MARK: link-computer-scan

    /// Action open: the Bipe support gate (never Client.isLoginWithQrCodeSupported, ADR 0118), then the camera.
    func open() async {
        support = .checking
        let serverSupports = await matrix?.supportsQRLogin() ?? false
        // Only whether an item exists: the value is read after the owner check.
        let hasPassword = BipeCredentialStore().password(for: session.userId) != nil
        switch (serverSupports, hasPassword) {
        case (true, true): support = .supported
        // A beta account without a hidden password on this phone needs the PIN first (ADR 0057).
        case (true, false): support = .needsPin
        default: support = .notSupported
        }
        guard support == .supported else { return }
        switch mode {
        case .computer:
            await loadPeople()
            await askCamera()
        case .phone: generate()
        }
    }

    /// Spec link-computer-approve pickPerson. A personal account answers not_found and the list stays empty.
    private func loadPeople() async {
        guard let list = try? await session.business(settings.api).seats(), list.me.role == "owner",
              list.activeSeats.count > 1 else { return }
        people = list.activeSeats
        pickedSeatId = list.me.seatId
    }

    private func askCamera() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            scanPhase = .scanning
        case .notDetermined:
            scanPhase = await AVCaptureDevice.requestAccess(for: .video) ? .scanning : .cameraDenied
        default:
            scanPhase = .cameraDenied
        }
    }

    /// Action codeRead: only a login code from a new device starts the grant.
    func codeRead(_ bytes: Data) {
        guard scanPhase == .scanning || scanPhase == .failed, grantTask == nil else { return }
        // Only a login code whose rendezvous is on this account's homeserver (bipe-chat/bipe#785, ADR 0116).
        guard let data = try? QrCodeData.fromBytes(bytes: bytes), data.intent() == .login,
              BipeWebLink.isOwnRendezvous(qrBytes: bytes, baseURL: data.baseUrl(), homeserver: session.client.homeserver()) else {
            scanError = .notBipeCode
            scanPhase = .failed
            return
        }
        scanError = nil
        errorCode = nil
        scanPhase = .connecting
        startedAt = .now
        let handler = session.client.newGrantLoginWithQrCodeHandler()
        let progress = GrantProgressStream()
        Task { [weak self] in
            for await update in progress.updates {
                self?.progress(update)
            }
        }
        grantTask = Task { [weak self] in
            do {
                try await handler.scan(qrCodeData: data, progressListener: progress)
                progress.finish()
                self?.progress(.done)
            } catch {
                progress.finish()
                self?.taskFailed(error)
            }
        }
    }

    /// Action progress.
    private func progress(_ update: GrantQrLoginProgress) {
        switch update {
        case .starting:
            break
        case .establishingSecureChannel(_, let checkCodeString):
            checkCode = checkCodeString
            scanPhase = .showCheckCode
        case .waitingForAuth(let verificationUri, let continuationSender):
            verificationURL = URL(string: verificationUri)
            self.continuationSender = continuationSender
            step = .warning
        case .syncingSecrets:
            approvePhase = .finishing
        case .done:
            approvePhase = .done
            Task { await finish() }
        }
    }

    /// Action taskFailed: maps the SDK error to a string.
    private func taskFailed(_ error: Error) {
        grantTask = nil
        let failure: Failure
        let grantError = error as? HumanQrGrantLoginError
        errorCode = grantError.map { "\($0)".components(separatedBy: "(").first ?? "" }
        switch grantError {
        case .NotFound, .Expired: failure = .expired
        case .InvalidCheckCode where mode == .phone: failure = .wrongCheckCode
        case .ConnectionInsecure, .InvalidCheckCode: failure = .insecure
        case .MissingSecretsBackup(let detail):
            let cause = Self.secretsCause(detail)
            failure = cause.0
            errorCode = cause.1
        case .Cancelled: failure = .stopped
        case .UnsupportedQrCodeType, .UnsupportedProtocol, .OtherDeviceAlreadySignedIn: failure = .notBipeCode
        default: failure = error is URLError ? .network : .generic
        }
        Self.log.error("link failed (\(self.errorCode ?? "-", privacy: .public)): \(String(describing: error), privacy: .public)")
        switch step {
        case .scan, .show, .warning:
            guard !isClosed else { return }
            switch mode {
            case .computer:
                step = .scan
                scanError = failure
                scanPhase = .failed
            case .phone:
                // A second error for the same try (send, then the task) keeps the first.
                guard showPhase != .failed else { return }
                step = .show
                showError = failure
                showPhase = .failed
            }
        case .approve:
            // Cancelled after reject: the user declined.
            if failure == .stopped && rejected {
                close()
                return
            }
            approveError = failure == .expired ? .expired : .generic
            approvePhase = .failed
            Task { await finish() }
        }
    }

    /// Action scanAgain: drops the task and scans again.
    func scanAgain() {
        grantTask?.cancel()
        grantTask = nil
        continuationSender = nil
        checkCode = nil
        scanError = nil
        errorCode = nil
        step = .scan
        scanPhase = .scanning
    }

    // MARK: link-phone-show

    /// Action open (mode phone): GrantLoginWithQrCodeHandler.generate; the code arrives with qrReady.
    private func generate() {
        guard grantTask == nil else { return }
        showError = nil
        errorCode = nil
        showPhase = .preparing
        startedAt = .now
        codeAttempt += 1
        let current = codeAttempt
        let handler = session.client.newGrantLoginWithQrCodeHandler()
        let progress = GrantGeneratedProgressStream()
        Task { [weak self] in
            for await update in progress.updates where self?.codeAttempt == current {
                self?.generatedProgress(update)
            }
        }
        grantTask = Task { [weak self] in
            do {
                try await handler.generate(progressListener: progress)
                progress.finish()
                guard self?.codeAttempt == current else { return }
                self?.generatedProgress(.done)
            } catch {
                progress.finish()
                guard self?.codeAttempt == current else { return }
                self?.taskFailed(error)
            }
        }
    }

    /// Action progress (mode phone).
    private func generatedProgress(_ update: GrantGeneratedQrLoginProgress) {
        switch update {
        case .starting:
            break
        case .qrReady(let qrCode):
            qrBytes = qrCode.toBytes()
            showPhase = .showingCode
        case .qrScanned(let sender):
            checkCodeSender = sender
            requestedAt = .now
            typedCheckCode = ""
            showPhase = .enterCheckCode
        case .waitingForAuth(let verificationUri, let continuationSender):
            verificationURL = URL(string: verificationUri)
            self.continuationSender = continuationSender
            step = .warning
        case .syncingSecrets:
            approvePhase = .finishing
        case .done:
            approvePhase = .done
            onGrantDone?()
            Task { await finish() }
        }
    }

    /// Action editCheckCode: ASCII digits, at most 2; at 2 digits, submitCheckCode.
    func editCheckCode(_ text: String) {
        guard showPhase == .enterCheckCode else { return }
        typedCheckCode = String(text.filter { $0.isASCII && $0.isNumber }.prefix(2))
        guard typedCheckCode.count == 2, let code = UInt8(typedCheckCode), let sender = checkCodeSender else { return }
        showPhase = .checking
        checkCodeSender = nil
        let current = codeAttempt
        Task { [weak self] in
            do {
                try await sender.send(code: code)
            } catch {
                // A wrong number also ends the generate task with invalidCheckCode.
                guard self?.codeAttempt == current else { return }
                self?.taskFailed(error)
            }
        }
    }

    /// Action newCode: drops the handler and makes a new code.
    func newCode() {
        codeAttempt += 1
        grantTask?.cancel()
        grantTask = nil
        continuationSender = nil
        checkCodeSender = nil
        qrBytes = nil
        typedCheckCode = ""
        requestedAt = nil
        step = .show
        generate()
    }

    // MARK: link-computer-warning

    /// Action appear: the haptic, then the countdown from the start, also when the screen comes back to the front.
    func warningAppeared() {
        countdown?.cancel()
        secondsLeft = BipeRegistries.webLinkCooldownSeconds
        countdown = Task { [weak self] in
            while let self, self.secondsLeft > 0 {
                guard (try? await Task.sleep(for: .seconds(1))) != nil else { return }
                self.secondsLeft -= 1
            }
        }
    }

    func warningLeftFront() {
        countdown?.cancel()
    }

    /// Action continue: only when canContinue.
    func continueToApprove() {
        guard canContinue else { return }
        countdown?.cancel()
        approvePhase = .confirmIdentity
        step = .approve
    }

    /// Action approvePhone: closes the flow; the view then opens the encryption-status sheet.
    func approvePhone() {
        opensApproval = true
        cancel()
    }

    /// Action cancel (scan and warning): the computer does not sign in.
    func cancel() {
        isClosed = true
        let sender = continuationSender
        continuationSender = nil
        checkCodeSender = nil
        grantTask?.cancel()
        grantTask = nil
        countdown?.cancel()
        Task { try? await sender?.cancel() }
    }

    // MARK: link-computer-approve

    /// Action confirmIdentity: the owner check (ApprovalCheck: biometrics, else the Bipe PIN; never the passcode,
    /// rule 14 tier 3), then the hidden password. A fail, or a phone with no screen lock, goes back to the warning.
    func confirmIdentity() async {
        let reason = mode == .computer ? BipeStrings.linkComputerBiometric : BipeStrings.appLinkPhoneBiometric
        let api = settings.api
        let settings = settings
        let pinReason = mode == .computer ? BipeStrings.pinReasonLinkComputer(String(BipeRegistries.pinLength)) : BipeStrings.pinReasonApproveDevice(String(BipeRegistries.pinLength))
        let check = await ApprovalCheck.run(reason: reason, pinTitle: BipeStrings.pinEnterTitle, pinReason: pinReason,
                                            prompt: pinPrompt, account: settings.session.accountRead(api)) { (pin: String) async throws(BipeAPIError) in
            try await settings.authorized { (token: String) async throws(BipeAPIError) in
                try await api.checkPIN(pin, accessToken: token)
            }
        }
        guard check == .passed, let secret = hiddenPassword else {
            step = .warning
            warningAppeared()
            return
        }
        password = secret
        approvePhase = .loading
        // Phase loading removes the view whose .task runs this method, and SwiftUI cancels that task. A cancelled
        // task cancels the auth metadata request (-999), and the flow then cancels the new device's login.
        // The consent load therefore runs in its own task.
        Task { await openConsent() }
    }

    /// Action open: the MAS host from the auth metadata; the verification page must be on it. Then the MAS pages
    /// with the hidden password, up to the consent page (ADR 0224).
    private func openConsent() async {
        guard let metadata = try? await matrix?.authMetadata(),
              let host = metadata.host,
              let url = verificationURL,
              BipeWebLink.isAllowed(url, host: host),
              let secret = password else {
            Self.log.error("link failed (consent-host): no auth metadata or the page is off the MAS host")
            errorCode = "consent-host"
            fail(.signIn)
            return
        }
        password = nil
        masHost = host
        let approval = MASDeviceApproval(host: host)
        self.approval = approval
        do throws(MASDeviceApproval.Failure) {
            let consent = try await approval.open(url, username: BipeWebLink.localpart(of: session.userId), password: secret)
            guard approvePhase == .loading else { return }
            self.consent = consent
            device = consent.device
            ip = consent.ip
            approvePhase = .consent
            if let ip = consent.ip {
                await lookUpPlace(ip: ip)
            } else {
                placeKnown = true
            }
        } catch {
            approvalFailed(error)
        }
    }

    /// Action approve: posts the MAS consent form; MAS then grants the computer's request.
    func approve() {
        guard approvePhase == .consent, let approval, let consent else { return }
        approvePhase = .finishing
        Task {
            do throws(MASDeviceApproval.Failure) {
                try await approval.approve(consent)
                approved = true
                try? await continuationSender?.confirm()
            } catch {
                approvalFailed(error)
            }
        }
    }

    /// Action reject: posts the MAS reject form, then the SDK cancel closes the flow with no message.
    func reject() {
        guard approvePhase == .consent, let approval, let consent else { return }
        rejected = true
        isClosed = true
        let sender = continuationSender
        Task {
            await approval.reject(consent)
            try? await sender?.cancel()
            await finish()
        }
    }

    private func approvalFailed(_ error: MASDeviceApproval.Failure) {
        errorCode = error.code
        if case .notConsentPage(let path) = error {
            Self.log.error("link failed (\(error.code, privacy: .public)): page \(path, privacy: .public)")
        } else {
            Self.log.error("link failed (\(error.code, privacy: .public))")
        }
        fail(error == .network ? .network : .signIn)
    }

    /// Action close.
    func close() {
        let sender = continuationSender
        let confirm = approved
        isClosed = true
        Task {
            if confirm {
                try? await sender?.confirm()
            } else {
                try? await sender?.cancel()
            }
            await finish()
        }
    }

    /// Action retry: a new QR code is needed for each try.
    func retry() {
        Task { await finish() }
        password = nil
        approved = false
        rejected = false
        device = nil
        ip = nil
        consent = nil
        place = nil
        placeKnown = false
        approveError = nil
        verificationURL = nil
        masHost = nil
        finished = false
        switch mode {
        case .computer: scanAgain()
        case .phone: newCode()
        }
    }

    private func fail(_ failure: Failure) {
        password = nil
        approveError = failure
        approvePhase = .failed
        let sender = continuationSender
        Task {
            try? await sender?.cancel()
            await finish()
        }
    }

    private func lookUpPlace(ip: String) async {
        let api = settings.api
        let found = try? await settings.authorized { (token: String) async throws(BipeAPIError) -> BipeWebLinkPlace in
            try await api.webLinkPlace(ip: ip, accessToken: token)
        }
        place = found?.label()
        placeKnown = true
    }

    /// Action finish: once per flow. The approval drops its HTTP session and cookies; bipe-api ends the MAS browser
    /// sessions it made. A failure is logged and not shown.
    private func finish() async {
        guard !finished, masHost != nil else { return }
        finished = true
        approval?.close()
        approval = nil
        consent = nil
        let api = settings.api
        let since = startedAt
        let kind: WebLinkKind = mode == .computer ? .computer : .phone
        let seatId = person?.seatId ?? pickedSeatId
        do {
            try await settings.authorized { (token: String) async throws(BipeAPIError) in
                try await api.finishWebLink(since: since, kind: kind, seatId: seatId, accessToken: token)
            }
        } catch {
            NSLog("Bipe web-link finish failed")
        }
    }
}
