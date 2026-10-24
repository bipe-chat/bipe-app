// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Foundation
import MatrixRustSDK

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
/// the phone scans the QR code of chat.bipe.net.br, opens the MSC4108 secure channel, warns, waits through the
/// cooldown, checks the owner and approves on the MAS consent page in a private web view (ADR 0116, ADR 0118).
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
        case needsRecoveryKey
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
            case (.needsRecoveryKey, .computer): BipeStrings.appLinkComputerNeedsRecoveryKey
            case (.needsRecoveryKey, .phone): BipeStrings.appLinkPhoneNeedsRecoveryKey
            case (.wrongCheckCode, _): BipeStrings.appLinkPhoneWrongCheckCode
            case (.stopped, _): BipeStrings.appLinkComputerStopped
            case (.network, _): BipeStrings.errorNetwork
            case (.signIn, _): BipeStrings.linkComputerErrorSignIn
            case (.generic, _): BipeStrings.errorGeneric
            }
        }
    }

    /// Debug builds and the Simulator only: the copy button of link-phone-show (bipe-chat/bipe#608).
    static var canCopyCode: Bool {
        #if targetEnvironment(simulator) || DEBUG
        true
        #else
        false
        #endif
    }

    let mode: Mode

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
    @Published private(set) var approveError: Failure?
    /// The flow ended: the view closes.
    @Published private(set) var isClosed = false

    let settings: SettingsModel
    var session: AccountSession { settings.session }
    /// The page the web view opens, and the only host it loads.
    private(set) var verificationURL: URL?
    private(set) var masHost: String?
    private var continuationSender: ContinuationMessageSender?
    private var checkCodeSender: CheckCodeSender?
    /// Mode phone: each code has its own number, so that a dropped code never touches the next one.
    private var codeAttempt = 0
    private var grantTask: Task<Void, Never>?
    private var countdown: Task<Void, Never>?
    private var startedAt = Date.now
    private var filledLoginForm = false
    private var sawConsentPage = false
    private var finished = false
    /// Held only between the owner check and the one submit of the MAS login form.
    private var password: String?

    init(settings: SettingsModel, mode: Mode = .computer) {
        self.settings = settings
        self.mode = mode
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
        case .computer: await askCamera()
        case .phone: generate()
        }
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
        guard let data = try? QrCodeData.fromBytes(bytes: bytes), data.intent() == .login else {
            scanError = .notBipeCode
            scanPhase = .failed
            return
        }
        scanError = nil
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
        switch error as? HumanQrGrantLoginError {
        case .NotFound, .Expired: failure = .expired
        case .InvalidCheckCode where mode == .phone: failure = .wrongCheckCode
        case .ConnectionInsecure, .InvalidCheckCode: failure = .insecure
        case .MissingSecretsBackup: failure = .needsRecoveryKey
        case .Cancelled: failure = .stopped
        case .UnsupportedQrCodeType, .UnsupportedProtocol, .OtherDeviceAlreadySignedIn: failure = .notBipeCode
        default: failure = error is URLError ? .network : .generic
        }
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
            // Cancelled after the consent page: the user declined on the MAS page.
            if failure == .stopped && sawConsentPage {
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
        step = .scan
        scanPhase = .scanning
    }

    // MARK: link-phone-show

    /// Action open (mode phone): GrantLoginWithQrCodeHandler.generate; the code arrives with qrReady.
    private func generate() {
        guard grantTask == nil else { return }
        showError = nil
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

    /// Action confirmIdentity: the biometric check (or the device code), then the hidden password.
    func confirmIdentity() async {
        let reason = mode == .computer ? BipeStrings.linkComputerBiometric : BipeStrings.appLinkPhoneBiometric
        guard await OwnerCheck.owner(reason: reason), let secret = hiddenPassword else {
            step = .warning
            warningAppeared()
            return
        }
        password = secret
        approvePhase = .loading
        await openConsent()
    }

    /// Action open: the MAS host from the auth metadata; the verification page must be on it.
    private func openConsent() async {
        guard let metadata = try? await matrix?.authMetadata(),
              let host = metadata.host,
              let url = verificationURL,
              BipeWebLink.isAllowed(url, host: host) else {
            fail(.signIn)
            return
        }
        masHost = host
    }

    /// What the web view found on a loaded page.
    struct Page {
        var url: URL
        var hasPasswordField: Bool
        var hasForm: Bool
        var text: String
    }

    /// The script that fills the MAS login form once. nil when the form was filled before or no password is held.
    func pageLoaded(_ page: Page) -> String? {
        if page.hasPasswordField {
            guard !filledLoginForm, let password,
                  let script = BipeWebLink.loginScript(username: BipeWebLink.localpart(of: session.userId), password: password) else {
                // A second login form: a wrong password or a MAS rate limit.
                fail(.signIn)
                return nil
            }
            filledLoginForm = true
            self.password = nil
            return script
        }
        if BipeWebLink.isDeviceConsentPath(page.url) && page.hasForm {
            if !sawConsentPage {
                sawConsentPage = true
                approvePhase = .consent
                if let ip = BipeWebLink.firstIPAddress(in: page.text) {
                    Task { await lookUpPlace(ip: ip) }
                } else {
                    placeKnown = true
                }
            }
            return nil
        }
        if sawConsentPage && approvePhase == .consent {
            // The user approved or declined; the SDK learns the result from MAS.
            approvePhase = .finishing
            let sender = continuationSender
            Task {
                try? await sender?.confirm()
                await finish()
            }
        }
        return nil
    }

    /// A navigation off the MAS host stops the load.
    func blockedNavigation() {
        fail(.signIn)
    }

    /// Action close.
    func close() {
        let sender = continuationSender
        let confirm = sawConsentPage
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
        filledLoginForm = false
        sawConsentPage = false
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

    /// Action finish: once per flow. The view drops its non-persistent data store; bipe-api ends the MAS browser
    /// sessions the web view made. A failure is logged and not shown.
    private func finish() async {
        guard !finished, masHost != nil else { return }
        finished = true
        let api = settings.api
        let since = startedAt
        do {
            try await settings.authorized { (token: String) async throws(BipeAPIError) in
                try await api.finishWebLink(since: since, accessToken: token)
            }
        } catch {
            NSLog("Bipe web-link finish failed")
        }
    }
}
