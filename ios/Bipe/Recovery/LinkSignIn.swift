// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AuthenticationServices
import BipeKit
import UIKit

/// The provider sign-in of a linked Apple or Google account (ADR 0096; specs linked-accounts, recovery-linked and
/// signup-fallback). Apple: Sign in with Apple with a nonce from the Bipe API, no scopes (no name, no email). Google: the
/// Bipe API's web flow in ASWebAuthenticationSession, which comes back to the app's own scheme. No Google SDK.
@MainActor
enum LinkSignIn {
    enum Outcome {
        case proof(BipeLinkProof)
        case cancelled
        case failed
    }

    /// The app's own URL scheme for the web flow: the bundle ID (the API knows the Bipe IDs).
    static var appId: String {
        Bundle.main.bundleIdentifier ?? "chat.bipe.app.next"
    }

    /// Runs the sign-in. `accessToken` set: a link for the signed-in account; `nil`: a recovery.
    static func run(_ provider: BipeLinkProvider, api: BipeAPIClient, accessToken: String?) async throws(BipeAPIError) -> Outcome {
        switch provider {
        case .apple:
            let nonce = try await api.linkNonce(accessToken: accessToken)
            return await AppleSignIn().run(nonce: nonce.nonce)
        case .google:
            let start = try await api.startLinkWebFlow(.google, appId: appId, accessToken: accessToken)
            return await WebSignIn().run(start)
        }
    }
}

/// Sign in with Apple. The nonce goes in as it is; the Bipe API checks it in the ID token.
@MainActor
private final class AppleSignIn: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private var continuation: CheckedContinuation<LinkSignIn.Outcome, Never>?

    func run(nonce: String) async -> LinkSignIn.Outcome {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        // No scopes: Apple sends no name and no email (ADR 0096).
        request.requestedScopes = []
        request.nonce = nonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        return await withCheckedContinuation { continuation in
            self.continuation = continuation
            controller.performRequests()
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        let token = (authorization.credential as? ASAuthorizationAppleIDCredential)?.identityToken.flatMap { String(data: $0, encoding: .utf8) }
        MainActor.assumeIsolated {
            finish(token.map { .proof(.idToken($0)) } ?? .failed)
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        MainActor.assumeIsolated {
            finish(cancelled ? .cancelled : .failed)
        }
    }

    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated { WebSignIn.keyWindow() }
    }

    private func finish(_ outcome: LinkSignIn.Outcome) {
        continuation?.resume(returning: outcome)
        continuation = nil
    }
}

/// The web flow. The session ends on the redirect to `<bundle id>:/link-done`.
@MainActor
private final class WebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func run(_ start: BipeLinkWebStart) async -> LinkSignIn.Outcome {
        let scheme = LinkSignIn.appId
        let outcome: LinkSignIn.Outcome = await withCheckedContinuation { continuation in
            let session = ASWebAuthenticationSession(url: start.url, callbackURLScheme: scheme) { url, error in
                if let url, let done = BipeLinkDone(url: url, scheme: scheme) {
                    switch done {
                    case .done(let flowId, let handoff) where flowId == start.flowId:
                        continuation.resume(returning: .proof(.web(flowId: flowId, handoff: handoff)))
                    case .cancelled:
                        continuation.resume(returning: .cancelled)
                    default:
                        continuation.resume(returning: .failed)
                    }
                } else if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    continuation.resume(returning: .cancelled)
                } else {
                    continuation.resume(returning: .failed)
                }
            }
            // No shared cookies: the sign-in is for this link only.
            session.prefersEphemeralWebBrowserSession = true
            session.presentationContextProvider = self
            self.session = session
            if !session.start() {
                continuation.resume(returning: .failed)
            }
        }
        session = nil
        return outcome
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { Self.keyWindow() }
    }

    static func keyWindow() -> UIWindow {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.first.map { UIWindow(windowScene: $0) } ?? UIWindow()
    }
}
