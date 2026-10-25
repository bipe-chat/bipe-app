// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

// A linked Apple or Google account as a recovery factor (ADR 0096; specs linked-accounts and recovery-linked).
// iOS signs in with Apple natively (AuthenticationServices) and runs the web flow for Google (ASWebAuthenticationSession),
// with no Google SDK. Nothing here prints an ID token, a nonce, a flow ID or a handoff.

/// Contract: `LinkProvider.provider`.
public enum BipeLinkProvider: String, Codable, Sendable, CaseIterable, Identifiable {
    case apple, google

    public var id: String { rawValue }
}

/// Contract: `LinkProvider`. The paths the server offers for one provider.
public struct BipeLinkProviderPaths: Decodable, Sendable, Equatable {
    public let provider: BipeLinkProvider
    public let native: Bool
    public let web: Bool
}

/// Contract: `LinkProviders`.
public struct BipeLinkProviders: Decodable, Sendable, Equatable {
    public let providers: [BipeLinkProviderPaths]

    /// iOS: Apple through Sign in with Apple (native), Google through the web flow.
    public var usableOnIOS: [BipeLinkProvider] {
        providers.filter { $0.provider == .apple ? $0.native : $0.web }.map(\.provider)
    }
}

/// Contract: `LinkedAccount`.
public struct BipeLinkedAccount: Decodable, Sendable, Equatable {
    public let provider: BipeLinkProvider
    public let linkedAt: Date
}

/// Contract: `LinkEvent`. The other devices show each one once (recovery-alert).
public struct BipeLinkEvent: Decodable, Sendable, Equatable, Identifiable {
    public enum Change: String, Decodable, Sendable {
        case linked, unlinked
    }

    public let id: String
    public let provider: BipeLinkProvider
    public let change: Change
    public let at: Date

    /// The notice text for the other devices.
    public var alertMessage: String {
        switch (provider, change) {
        case (.apple, .linked): BipeStrings.linkedAppleAlert
        case (.google, .linked): BipeStrings.linkedGoogleAlert
        case (.apple, .unlinked): BipeStrings.unlinkedAppleAlert
        case (.google, .unlinked): BipeStrings.unlinkedGoogleAlert
        }
    }
}

/// Contract: `LinkNonce`. Single use.
public struct BipeLinkNonce: Decodable, Sendable, CustomStringConvertible {
    public let nonce: String
    public let expiresAt: Date

    public var description: String { "BipeLinkNonce(redacted)" }
}

/// Contract: `LinkWebStart`.
public struct BipeLinkWebStart: Decodable, Sendable, CustomStringConvertible {
    public let flowId: String
    public let url: URL
    public let expiresAt: Date

    public var description: String { "BipeLinkWebStart(redacted)" }
}

/// Contract: `LinkProof`. Exactly one of the ID token, or the flow ID with the handoff. Single use.
public enum BipeLinkProof: Sendable, CustomStringConvertible {
    case idToken(String)
    case web(flowId: String, handoff: String)

    public var description: String { "BipeLinkProof(redacted)" }

    fileprivate var body: LinkProofBody {
        switch self {
        case .idToken(let token): LinkProofBody(idToken: token, flowId: nil, handoff: nil)
        case .web(let flowId, let handoff): LinkProofBody(idToken: nil, flowId: flowId, handoff: handoff)
        }
    }
}

private struct LinkProofBody: Encodable {
    let idToken: String?
    let flowId: String?
    let handoff: String?
}

private struct LinkWebRequest: Encodable {
    let appId: String
}

private struct RecoveryLinkRequest: Encodable {
    let provider: BipeLinkProvider
    let idToken: String?
    let flowId: String?
    let handoff: String?
    let pin: String
}

public extension BipeAPIClient {
    /// `GET /v1/links/providers`. No auth.
    func linkProviders() async throws(BipeAPIError) -> BipeLinkProviders {
        try await send(method: "GET", path: ["v1", "links", "providers"], body: Empty?.none)
    }

    /// `POST /v1/me/links/nonce` (signed in) or `POST /v1/recovery/link/nonce` (no auth). For Sign in with Apple.
    func linkNonce(accessToken: String?) async throws(BipeAPIError) -> BipeLinkNonce {
        let path = accessToken != nil ? ["v1", "me", "links", "nonce"] : ["v1", "recovery", "link", "nonce"]
        return try await send(method: "POST", path: path, body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/me/links/{provider}/web` (signed in) or `POST /v1/recovery/link/{provider}/web` (no auth).
    func startLinkWebFlow(_ provider: BipeLinkProvider, appId: String, accessToken: String?) async throws(BipeAPIError) -> BipeLinkWebStart {
        let path = accessToken != nil ? ["v1", "me", "links", provider.rawValue, "web"] : ["v1", "recovery", "link", provider.rawValue, "web"]
        return try await send(method: "POST", path: path, body: LinkWebRequest(appId: appId), accessToken: accessToken)
    }

    /// `PUT /v1/me/links/{provider}`. The app checks the device owner first.
    func link(_ provider: BipeLinkProvider, proof: BipeLinkProof, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "PUT", path: ["v1", "me", "links", provider.rawValue], body: proof.body, accessToken: accessToken)
    }

    /// `DELETE /v1/me/links/{provider}`. The app checks the device owner first.
    func unlink(_ provider: BipeLinkProvider, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "DELETE", path: ["v1", "me", "links", provider.rawValue], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/recovery/link`. No auth. The provider proof, then the PIN; the delay starts.
    func recoverWithLink(_ provider: BipeLinkProvider, proof: BipeLinkProof, pin: String) async throws(BipeAPIError) -> BipeRecoveryStarted {
        let body = proof.body
        return try await send(method: "POST", path: ["v1", "recovery", "link"],
                              body: RecoveryLinkRequest(provider: provider, idToken: body.idToken, flowId: body.flowId, handoff: body.handoff, pin: pin))
    }
}

/// The end of a web flow: `<bundle id>:/link-done?flow=<id>#<handoff>`, or `?flow=<id>&error=<reason>`.
public enum BipeLinkDone: Equatable, Sendable, CustomStringConvertible {
    case done(flowId: String, handoff: String)
    case cancelled(flowId: String)
    case failed(flowId: String)

    public var description: String { "BipeLinkDone(redacted)" }

    /// `nil` when `url` is not a link-done URL for `scheme`.
    public init?(url: URL, scheme: String) {
        guard url.scheme == scheme,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.path == "/link-done",
              let flow = components.queryItems?.first(where: { $0.name == "flow" })?.value,
              Self.isSafe(flow) else { return nil }
        if let error = components.queryItems?.first(where: { $0.name == "error" })?.value {
            self = error == "cancelled" ? .cancelled(flowId: flow) : .failed(flowId: flow)
        } else if let handoff = components.fragment, Self.isSafe(handoff) {
            self = .done(flowId: flow, handoff: handoff)
        } else {
            self = .failed(flowId: flow)
        }
    }

    private static func isSafe(_ value: String) -> Bool {
        (1...128).contains(value.count) && value.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
    }
}
