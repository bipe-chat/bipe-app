// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Observation

/// Trust step 5 (bipe#544, spec govbr-verify, bipe docs/identity.md): the user signs a Bipe challenge PDF in the gov.br app.
/// The server makes the PDF and checks the signed file. The app hands the PDF to the system save dialog, sends the user to gov.br
/// and uploads the file that comes back. Rules shared with Android: ``BipeGovbrRules`` (test-vectors/govbr-verify.json).
@MainActor @Observable
public final class BipeGovbrFlow: Identifiable {
    public enum Phase: Equatable {
        case ready
        case preparing
        /// The user saves `pdf` to Files and signs it in the gov.br app.
        case waitingForSignature(pdf: Data, fileName: String)
        case checking
        /// `name` is the verified name from the new badge, when the server sent one.
        case verified(name: String?)
        case rejected(BipeGovbrReason)
        case failed
        
        /// The phase name of the spec and the test vectors.
        public var id: String {
            switch self {
            case .ready: "ready"
            case .preparing: "preparing"
            case .waitingForSignature: "waitingForSignature"
            case .checking: "checking"
            case .verified: "verified"
            case .rejected: "rejected"
            case .failed: "failed"
            }
        }
    }
    
    /// The gov.br app has no documented URL scheme. Its store page shows "Abrir" when the app is installed (registries/govbr.json).
    public static let govbrAppURL = URL(string: BipeRegistries.govbrIosStoreUrl)!
    /// The API's limit for the signed file.
    static let maxDocumentBytes = BipeRegistries.govbrMaxDocumentBytes
    
    public private(set) var phase = Phase.ready
    /// The level of the new badge after `verified`, read without the signature check: only to rebuild the owner's trust card.
    public private(set) var verifiedLevel: BadgeLevel?
    /// A signed file came while no check was open (rule noOpenVerification). The screen shows bipe_govbr_no_open_verification.
    public private(set) var showsNoOpenVerification = false
    
    private let apiClient: BipeAPIClient
    private let accessToken: @MainActor () -> String?
    private var verificationID: String?
    
    public init(apiClient: BipeAPIClient, accessToken: @escaping @MainActor () -> String?) {
        self.apiClient = apiClient
        self.accessToken = accessToken
    }
    
    /// Gets a new challenge. Also the "Tentar de novo" action: a new challenge is always valid.
    public func start() async {
        phase = .preparing
        showsNoOpenVerification = false
        verificationID = nil
        do {
            let challenge = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { [apiClient] (token: String) async throws(BipeAPIError) -> BipeGovbrChallenge in
                try await apiClient.startGovbrVerification(accessToken: token)
            }
            verificationID = challenge.verificationId
            phase = .waitingForSignature(pdf: challenge.pdf, fileName: challenge.fileName)
        } catch {
            phase = .failed
        }
    }
    
    /// The "Já assinei" picker: reads the picked file, then follows ``receive(pdf:)``.
    @discardableResult
    public func submit(fileURL: URL) async -> BipeGovbrRules.Incoming {
        let isScoped = fileURL.startAccessingSecurityScopedResource()
        let pdf = try? Data(contentsOf: fileURL)
        if isScoped {
            fileURL.stopAccessingSecurityScopedResource()
        }
        return await receive(pdf: pdf ?? Data())
    }
    
    /// A signed file from the share sheet, open with, or the picker (spec govbr-verify, action receiveDocument).
    /// Uploads it only when the rule says upload. Returns the rule's answer.
    @discardableResult
    public func receive(pdf: Data) async -> BipeGovbrRules.Incoming {
        let incoming = BipeGovbrRules.incoming(phase: phase.id, byteCount: pdf.count)
        switch incoming {
        case .ignore:
            return incoming
        case .noOpenVerification:
            showsNoOpenVerification = true
            return incoming
        case .notSigned:
            phase = .rejected(.notSigned)
            return incoming
        case .upload:
            break
        }
        guard let verificationID else {
            phase = .failed
            return incoming
        }
        showsNoOpenVerification = false
        phase = .checking
        do {
            let status = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { [apiClient] (token: String) async throws(BipeAPIError) -> BipeVerificationStatus in
                try await apiClient.sendGovbrDocument(verificationID: verificationID, pdf: pdf, accessToken: token)
            }
            switch BipeGovbrRules.outcome(state: status.state.rawValue, reason: status.reasonID) {
            case .verified:
                verifiedLevel = status.badge?.unverifiedLevel
                phase = .verified(name: status.badge?.verifiedName)
            case .rejected(let reason):
                phase = .rejected(reason)
            case .failed:
                phase = .failed
            }
        } catch {
            phase = .failed
        }
        return incoming
    }
}

/// The rules both apps follow (bipe-shared tools/lib/govbr.mjs, test-vectors/govbr-verify.json).
public nonisolated enum BipeGovbrRules {
    /// What the app does with a PDF that arrives.
    public enum Incoming: String, Sendable {
        /// Send it with `PUT .../document`.
        case upload
        /// Show bipe_govbr_no_open_verification. Nothing is uploaded.
        case noOpenVerification
        /// A check runs: drop the file without a message.
        case ignore
        /// Empty or too big: shown as the reason not_signed, without an upload.
        case notSigned
    }
    
    /// The phase after a `VerificationStatus` answer.
    public enum Outcome: Equatable, Sendable {
        case verified
        case rejected(BipeGovbrReason)
        case failed
    }
    
    /// `phase` is a ``BipeGovbrFlow/Phase/id``.
    public static func incoming(phase: String, byteCount: Int, maxDocumentBytes: Int = BipeRegistries.govbrMaxDocumentBytes) -> Incoming {
        if phase == "checking" { return .ignore }
        guard phase == "waitingForSignature" else { return .noOpenVerification }
        return byteCount <= 0 || byteCount > maxDocumentBytes ? .notSigned : .upload
    }
    
    /// A rejection without a reason, and the state expired, is expired. A reason this build does not know fails closed.
    public static func outcome(state: String, reason: String?) -> Outcome {
        switch state {
        case "verified":
            return .verified
        case "rejected":
            guard let reason else { return .rejected(.expired) }
            return BipeGovbrReason(rawValue: reason).map(Outcome.rejected) ?? .failed
        case "expired":
            return .rejected(.expired)
        default:
            return .failed
        }
    }
}

// MARK: - Contract

/// `GovbrChallenge`. `pdf` arrives as base64 and decodes to the file bytes.
public nonisolated struct BipeGovbrChallenge: Sendable, Decodable {
    public let verificationId: String
    public let pdf: Data
    public let fileName: String
    public let expiresAt: Date
}

/// `VerificationStatus`.
public nonisolated struct BipeVerificationStatus: Sendable, Decodable {
    public enum State: String, Sendable, Decodable {
        case pending
        case verified
        case rejected
        case expired
    }
    
    public let verificationId: String
    public let state: State
    public let badge: BadgeEnvelope?
    /// `reason` as sent: a value this build does not know must not break the decoding (``BipeGovbrRules/outcome(state:reason:)``).
    public let reasonID: String?
    
    public var reason: BipeGovbrReason? {
        reasonID.flatMap(BipeGovbrReason.init(rawValue:))
    }
    
    private enum CodingKeys: String, CodingKey {
        case verificationId
        case state
        case badge
        case reasonID = "reason"
    }
}

/// `VerificationStatus.reason`.
public nonisolated enum BipeGovbrReason: String, Sendable, Decodable {
    case notSigned = "not_signed"
    case notGovbr = "not_govbr"
    case wrongDocument = "wrong_document"
    case expired
    case cpfInUse = "cpf_in_use"
    case changedAfterSigning = "changed_after_signing"
}

public extension BadgeEnvelope {
    /// The `name` in the payload, read without the signature check. Only for the owner's own screen, never to show a seal.
    nonisolated var verifiedName: String? {
        unverifiedPayload?.name
    }
    
    /// The `lvl` in the payload, read without the signature check. Only for the owner's own trust card, never to show a seal.
    nonisolated var unverifiedLevel: BadgeLevel? {
        unverifiedPayload?.lvl.flatMap(BadgeLevel.init(rawValue:))
    }
    
    private nonisolated struct LoosePayload: Decodable {
        let name: String?
        let lvl: String?
    }
    
    private nonisolated var unverifiedPayload: LoosePayload? {
        guard let bytes = Data(base64URLEncoded: payload) else { return nil }
        return try? JSONDecoder().decode(LoosePayload.self, from: bytes)
    }
}

public extension BipeBadgeLookup {
    /// The level of the envelope in a 200 answer, read without the signature check (see ``BadgeEnvelope/unverifiedLevel``).
    nonisolated var unverifiedLevel: BadgeLevel? {
        guard case .envelope(let data) = self else { return nil }
        return (try? JSONDecoder().decode(BadgeEnvelope.self, from: data))?.unverifiedLevel
    }
}
