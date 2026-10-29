// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Observation

/// Trust step 5 (bipe#544, bipe docs/identity.md): the user signs a Bipe challenge PDF in the gov.br app.
/// The server makes the PDF and checks the signed file. The app hands the PDF to the system save dialog, sends the user to gov.br
/// and uploads the file that comes back.
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
    }
    
    /// The gov.br app has no documented URL scheme. Its store page shows "Abrir" when the app is installed.
    public static let govbrAppURL = URL(string: BipeRegistries.govbrIosStoreUrl)!
    /// The API's limit for the signed file (registries/govbr.json).
    static let maxDocumentBytes = BipeRegistries.govbrMaxDocumentBytes
    
    public private(set) var phase = Phase.ready
    
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
    
    /// Uploads the signed file: from the share sheet or from the "Já assinei" picker.
    public func submit(fileURL: URL) async {
        guard let verificationID else {
            phase = .failed
            return
        }
        let isScoped = fileURL.startAccessingSecurityScopedResource()
        defer {
            if isScoped {
                fileURL.stopAccessingSecurityScopedResource()
            }
        }
        guard let pdf = try? Data(contentsOf: fileURL), pdf.count <= Self.maxDocumentBytes else {
            phase = .rejected(.notSigned)
            return
        }
        
        phase = .checking
        do {
            let status = try await BipeAPIClient.withTokenRetry(accessToken: accessToken) { [apiClient] (token: String) async throws(BipeAPIError) -> BipeVerificationStatus in
                try await apiClient.sendGovbrDocument(verificationID: verificationID, pdf: pdf, accessToken: token)
            }
            switch status.state {
            case .verified:
                phase = .verified(name: status.badge?.verifiedName)
            case .rejected, .expired:
                phase = .rejected(status.reason ?? .expired)
            case .pending:
                phase = .failed
            }
        } catch {
            phase = .failed
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
    public let reason: BipeGovbrReason?
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
