// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AVFoundation
import BipeKit
import Foundation
import MatrixRustSDK

/// Calls back on SDK threads; the model reads the updates in order on the main actor.
final class LoginProgressStream: QrLoginProgressListener {
    let updates: AsyncStream<QrLoginProgress>
    private let continuation: AsyncStream<QrLoginProgress>.Continuation

    init() {
        (updates, continuation) = AsyncStream.makeStream()
    }

    func onUpdate(state: QrLoginProgress) {
        continuation.yield(state)
    }

    func finish() {
        continuation.finish()
    }
}

/// Spec recovery-old-phone (bipe-shared product/screens): this new phone scans the code that the old phone shows on
/// link-phone-show (ADR 0059), shows the check code, waits for the approval on the old phone and signs in through
/// MAS. Cross-signing and the backup key arrive with the session, so no PIN and no identity screen follow.
@MainActor
final class OldPhoneModel: ObservableObject {
    enum Phase: Equatable {
        case cameraDenied
        case scanning
        case connecting
        case showCheckCode
        case waitingForApproval
        case syncing
        case failed
    }

    enum Failure: Equatable {
        case notBipeCode
        case expired
        case insecure
        case declined
        case notSupported
        case stopped
        case generic

        var message: String {
            switch self {
            case .notBipeCode: BipeStrings.appOldPhoneNotBipeCode
            case .expired: BipeStrings.appOldPhoneExpired
            case .insecure: BipeStrings.appOldPhoneInsecure
            case .declined: BipeStrings.appOldPhoneDeclined
            case .notSupported: BipeStrings.appOldPhoneNotSupported
            case .stopped: BipeStrings.appOldPhoneStopped
            case .generic: BipeStrings.errorGeneric
            }
        }
    }

    /// Debug builds and the Simulator only: the paste field next to the camera (bipe-chat/bipe#608).
    static var canPaste: Bool {
        #if targetEnvironment(simulator) || DEBUG
        true
        #else
        false
        #endif
    }

    @Published private(set) var phase: Phase = .scanning
    @Published private(set) var checkCode: String?
    @Published private(set) var error: Failure?
    @Published var pasted = ""

    private let onSignedIn: (AccountSession) -> Void
    private var task: Task<Void, Never>?
    /// Each try has its own number, so that a stopped try never touches the next one.
    private var attempt = 0

    init(onSignedIn: @escaping (AccountSession) -> Void) {
        self.onSignedIn = onSignedIn
    }

    /// Action open: asks for the camera. Without it, the paste field still works where canPaste.
    func open() async {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            phase = .scanning
        case .notDetermined:
            phase = await AVCaptureDevice.requestAccess(for: .video) ? .scanning : .cameraDenied
        default:
            phase = .cameraDenied
        }
    }

    /// Action codeRead: only a code that a signed-in device shows (QrCodeIntent.reciprocate) starts the login.
    func codeRead(_ bytes: Data) {
        guard task == nil, [.scanning, .failed, .cameraDenied].contains(phase) else { return }
        guard let data = try? QrCodeData.fromBytes(bytes: bytes), data.intent() == .reciprocate else {
            fail(.notBipeCode)
            return
        }
        error = nil
        checkCode = nil
        phase = .connecting
        attempt += 1
        let current = attempt
        let progress = LoginProgressStream()
        Task { [weak self] in
            for await update in progress.updates where self?.attempt == current {
                self?.progress(update)
            }
        }
        task = Task { [weak self] in
            do {
                let session = try await Self.login(data, progress: progress)
                progress.finish()
                // The login client is gone here: the store can move.
                let account = try await AccountSession.adoptQRLogin(session)
                self?.task = nil
                self?.onSignedIn(account)
            } catch {
                progress.finish()
                guard let self, self.attempt == current else { return }
                AccountSession.deleteStore(folder: AccountSession.qrLoginFolder)
                self.taskFailed(error)
            }
        }
    }

    /// LoginWithQrCodeHandler.scan in a fresh store. The client lives only in this call.
    private static func login(_ data: QrCodeData, progress: LoginProgressStream) async throws -> Session {
        let client = try await AccountSession.makeQRLoginClient(serverName: data.serverName())
        let handler = client.newLoginWithQrCodeHandler(oauthConfiguration: AccountSession.qrLoginOAuthConfiguration)
        try await handler.scan(qrCodeData: data, progressListener: progress)
        return try client.session()
    }

    /// Action submitPasted: base64 of the code bytes, as link-phone-show copies it.
    func submitPasted() {
        guard Self.canPaste else { return }
        let text = pasted.filter { !$0.isWhitespace }
        guard let bytes = Data(base64Encoded: text) else {
            fail(.notBipeCode)
            return
        }
        codeRead(bytes)
    }

    /// Action progress.
    private func progress(_ update: QrLoginProgress) {
        guard task != nil else { return }
        switch update {
        case .starting:
            break
        case .establishingSecureChannel(_, let checkCodeString):
            checkCode = checkCodeString
            phase = .showCheckCode
        case .waitingForToken:
            phase = .waitingForApproval
        case .syncingSecrets:
            phase = .syncing
        case .done:
            phase = .syncing
        }
    }

    /// Action taskFailed: maps the SDK error to a string.
    private func taskFailed(_ error: Error) {
        task = nil
        let failure: Failure
        switch error as? HumanQrLoginError {
        case .NotFound, .Expired: failure = .expired
        case .ConnectionInsecure, .CheckCodeAlreadySent, .CheckCodeCannotBeSent: failure = .insecure
        case .Declined: failure = .declined
        case .Cancelled: failure = .stopped
        case .LinkingNotSupported, .SlidingSyncNotAvailable, .OAuthMetadataInvalid: failure = .notSupported
        case .UnsupportedQrCodeType, .OtherDeviceNotSignedIn: failure = .notBipeCode
        default: failure = .generic
        }
        fail(failure)
    }

    private func fail(_ failure: Failure) {
        error = failure
        checkCode = nil
        phase = .failed
    }

    /// Action scanAgain.
    func scanAgain() {
        attempt += 1
        task?.cancel()
        task = nil
        error = nil
        checkCode = nil
        pasted = ""
        phase = .scanning
    }

    /// Action cancel: back to recovery-start. The next try deletes the fresh store.
    func cancel() {
        attempt += 1
        task?.cancel()
        task = nil
    }
}
