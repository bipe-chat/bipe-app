// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation

/// Verifies signed badge envelopes (bipe-shared/docs/badge-attestation.md, v1).
/// Must match bipe-shared/test-vectors/badges.json.
public nonisolated struct BadgeVerifier: Sendable {
    public enum KeyError: Error, Equatable {
        case invalidPublicKey(kid: String)
    }
    
    private let trustedKeys: [String: Data]
    
    /// - Parameter trustedKeys: `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
    public init(trustedKeys: [String: Data]) throws(KeyError) {
        for (kid, key) in trustedKeys where (try? Curve25519.Signing.PublicKey(rawRepresentation: key)) == nil {
            throw .invalidPublicKey(kid: kid)
        }
        self.trustedKeys = trustedKeys
    }
    
    /// The release trust list from bipe-shared `registries/badge-keys.json` (ADR 0123).
    public static func bipeTrusted() throws(KeyError) -> BadgeVerifier {
        var keys: [String: Data] = [:]
        for (kid, key) in BipeRegistries.badgeKeys {
            guard let raw = Data(base64URLEncoded: key) else { throw .invalidPublicKey(kid: kid) }
            keys[kid] = raw
        }
        return try BadgeVerifier(trustedKeys: keys)
    }
    
    /// Decodes and verifies envelope JSON. Undecodable JSON is `malformed`.
    /// - Parameter senderMasterKey: the sender's cross-signing master key from the SDK (`UserIdentity.masterKey()`), nil when unknown.
    public func verify(envelopeJSON: Data, sender: String, senderDevice: String, senderMasterKey: String?, now: Date = .now) -> BadgeVerificationResult {
        // Check `v` before the full shape: a future version may have other fields.
        guard let probe = try? JSONDecoder().decode(VersionProbe.self, from: envelopeJSON) else {
            return .invalid(.malformed)
        }
        guard probe.v == BadgeEnvelope.supportedVersion else {
            return .invalid(.unsupportedVersion)
        }
        guard let envelope = try? JSONDecoder().decode(BadgeEnvelope.self, from: envelopeJSON) else {
            return .invalid(.malformed)
        }
        return verify(envelope, sender: sender, senderDevice: senderDevice, senderMasterKey: senderMasterKey, now: now)
    }
    
    /// Runs the documented checks in order and returns the first failure.
    public func verify(_ envelope: BadgeEnvelope, sender: String, senderDevice: String, senderMasterKey: String?, now: Date = .now) -> BadgeVerificationResult {
        guard envelope.v == BadgeEnvelope.supportedVersion else {
            return .invalid(.unsupportedVersion)
        }
        guard let keyData = trustedKeys[envelope.kid],
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData) else {
            return .invalid(.unknownKID)
        }
        // Undecodable base64url is not a signature over any payload bytes, so it fails closed as malformed.
        guard let payloadBytes = Data(base64URLEncoded: envelope.payload),
              let signature = Data(base64URLEncoded: envelope.sig) else {
            return .invalid(.malformed)
        }
        // Verify the exact bytes. Never re-serialise the JSON before this check.
        guard publicKey.isValidSignature(signature, for: payloadBytes) else {
            return .invalid(.invalidSignature)
        }
        guard let payload = try? JSONDecoder().decode(BadgePayload.self, from: payloadBytes) else {
            return .invalid(.malformed)
        }
        
        let nowSeconds = now.timeIntervalSince1970
        if nowSeconds >= Double(payload.exp) {
            return .invalid(.expired)
        }
        if nowSeconds < Double(payload.iat) {
            return .invalid(.notYetValid)
        }
        guard payload.sub == sender else {
            return .invalid(.subjectMismatch)
        }
        switch payload.binding {
        case .masterKey(let msk):
            // A company or institution badge binds to the account, so any of its devices may send. No known master key fails closed.
            guard let senderMasterKey, msk == senderMasterKey else {
                return .invalid(.masterKeyMismatch)
            }
        case .device(let dev):
            guard dev == senderDevice else {
                return .invalid(.deviceMismatch)
            }
        }
        return .valid(payload)
    }
}

public nonisolated struct BadgeEnvelope: Sendable, Equatable, Codable {
    public static let supportedVersion = 1
    
    public let v: Int
    public let kid: String
    /// base64url without padding: the UTF-8 JSON payload bytes.
    public let payload: String
    /// base64url without padding: Ed25519 signature over the decoded payload bytes.
    public let sig: String
    
    public init(v: Int, kid: String, payload: String, sig: String) {
        self.v = v
        self.kid = kid
        self.payload = payload
        self.sig = sig
    }
}

public nonisolated enum BadgeLevel: String, Sendable, Codable {
    case pix
    case govbr
    case institution
    /// A verified company (ADR 0042), bound to the account's cross-signing master key.
    case cnpj
}

/// How a company proved control of its CNPJ (ADR 0042).
public nonisolated enum BadgeCNPJMethod: String, Sendable, Codable {
    case ecnpj
    case partner
    case staff
}

/// What a badge is bound to (badge-attestation.md, step 7).
public nonisolated enum BadgeBinding: Sendable, Equatable {
    /// Fingerprint of the device key (`dev`): `pix` and `govbr`.
    case device(String)
    /// The account's cross-signing master key (`msk`): `cnpj` and `institution`.
    case masterKey(String)
}

/// The company fields of a `cnpj` badge.
public nonisolated struct BadgeCompany: Sendable, Equatable {
    public let legalName: String
    public let tradeName: String?
    public let method: BadgeCNPJMethod
    /// The Receita Federal release that last showed the CNPJ as active, YYYY-MM.
    public let checkedMonth: String
}

public nonisolated struct BadgePayload: Sendable, Equatable, Decodable {
    /// Matrix user ID.
    public let sub: String
    public let lvl: BadgeLevel
    /// Verified name. For `cnpj`: the trade name when the registry has one, else the legal name.
    public let name: String
    /// Normalised CNPJ (14 characters, digits and uppercase letters). Present for `institution` and `cnpj`.
    public let cnpj: String?
    /// Present only for `cnpj`.
    public let company: BadgeCompany?
    /// `institution` only: the verified link domains (ADR 0036), lower case. Empty: no clickable link.
    public let domains: [String]
    public let binding: BadgeBinding
    /// `cnpj` only, optional: the CVM register line (ADR 0077). Read leniently: a bad value is no line, never a bad badge.
    public let cvm: BipeCvmField?
    /// Issued at, Unix seconds.
    public let iat: Int64
    /// Expires at, Unix seconds.
    public let exp: Int64
    
    private enum CodingKeys: String, CodingKey {
        case sub, lvl, name, cnpj, dev, msk, iat, exp
        case legalName = "legal_name"
        case tradeName = "trade_name"
        case method
        case checkedMonth = "checked_month"
        case cvm
        case domains
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sub = try container.decode(String.self, forKey: .sub)
        lvl = try container.decode(BadgeLevel.self, forKey: .lvl)
        name = try container.decode(String.self, forKey: .name)
        cnpj = try container.decodeIfPresent(String.self, forKey: .cnpj)
        iat = try container.decode(Int64.self, forKey: .iat)
        exp = try container.decode(Int64.self, forKey: .exp)
        cvm = lvl == .cnpj ? ((try? container.decodeIfPresent(BipeCvmField.self, forKey: .cvm)) ?? nil) : nil
        switch lvl {
        case .cnpj:
            let msk = try container.decode(String.self, forKey: .msk)
            let legalName = try container.decode(String.self, forKey: .legalName)
            let checkedMonth = try container.decode(String.self, forKey: .checkedMonth)
            guard let cnpj, cnpj.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil, !legalName.isEmpty, !msk.isEmpty,
                  checkedMonth.wholeMatch(of: /[0-9]{4}-(0[1-9]|1[0-2])/) != nil else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed cnpj badge"))
            }
            company = try BadgeCompany(legalName: legalName,
                                       tradeName: container.decodeIfPresent(String.self, forKey: .tradeName),
                                       method: container.decode(BadgeCNPJMethod.self, forKey: .method),
                                       checkedMonth: checkedMonth)
            binding = .masterKey(msk)
            domains = []
        case .institution:
            let msk = try container.decode(String.self, forKey: .msk)
            let domains = try container.decodeIfPresent([String].self, forKey: .domains) ?? []
            guard let cnpj, cnpj.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil, !msk.isEmpty,
                  domains.allSatisfy(BipeNoticeLinks.isDomain) else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed institution badge"))
            }
            company = nil
            self.domains = domains
            binding = .masterKey(msk)
        case .pix, .govbr:
            company = nil
            domains = []
            binding = try .device(container.decode(String.self, forKey: .dev))
        }
    }
}

public nonisolated enum BadgeVerificationResult: Sendable, Equatable {
    case valid(BadgePayload)
    case invalid(BadgeVerificationFailure)
}

/// Raw values are the codes in badge-attestation.md and test-vectors/badges.json.
public nonisolated enum BadgeVerificationFailure: String, Sendable, Equatable, Error {
    case unsupportedVersion = "unsupported_version"
    case unknownKID = "unknown_kid"
    case invalidSignature = "invalid_signature"
    case malformed
    case expired
    case notYetValid = "not_yet_valid"
    case subjectMismatch = "subject_mismatch"
    case deviceMismatch = "device_mismatch"
    case masterKeyMismatch = "master_key_mismatch"
}

// MARK: - Private

private nonisolated struct VersionProbe: Decodable {
    let v: Int
}

nonisolated extension Data {
    /// Strict base64url without padding (RFC 4648 section 5). Rejects padding and the standard alphabet.
    init?(base64URLEncoded string: String) {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        guard string.allSatisfy(allowed.contains), string.count % 4 != 1 else {
            return nil
        }
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        self.init(base64Encoded: base64)
    }
}
