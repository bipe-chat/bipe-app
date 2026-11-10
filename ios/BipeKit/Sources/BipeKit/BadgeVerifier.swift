// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Verifies signed badge envelopes (bipe-shared/docs/badge-attestation.md, v1) through bipe-core (ADR 0267).
/// ``bipeTrusted()`` checks against the current signed issuer registry (bipe-core `verifyIssuedBadge`, every step of
/// the verification order, bipe-chat/bipe#637). A verifier with a flat key list (tests, test-vectors/badges.json) uses
/// `verifyBoundSenderBadge`, which knows one issuer. Either way the app then reads the payload it shows.
public nonisolated struct BadgeVerifier: Sendable {
    public enum KeyError: Error, Equatable {
        case invalidPublicKey(kid: String)
    }

    private enum Trust: Sendable {
        case keys([String: Data])
        case registry(String)
    }

    private let trust: Trust

    /// - Parameter trustedKeys: `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
    public init(trustedKeys: [String: Data]) throws(KeyError) {
        for (kid, key) in trustedKeys where key.count != 32 {
            throw .invalidPublicKey(kid: kid)
        }
        trust = .keys(trustedKeys)
    }

    /// - Parameter issuerRegistryJSON: the payload JSON of a valid issuer registry (`VerifiedList.payloadJson`).
    public init(issuerRegistryJSON: String) {
        trust = .registry(issuerRegistryJSON)
    }

    /// The release trust: the newest valid issuer registry, bundled or fetched (``SignedLists``, bipe-chat/bipe#637).
    /// An app build always bundles one; the flat keys of `registries/badge-keys.json` (ADR 0123) are the last fallback.
    public static func bipeTrusted() throws(KeyError) -> BadgeVerifier {
        if let registry = SignedLists.shared.current(.issuers)?.payloadJson {
            return BadgeVerifier(issuerRegistryJSON: registry)
        }
        var keys: [String: Data] = [:]
        for (kid, key) in BipeRegistries.badgeKeys {
            guard let raw = Data(base64URLEncoded: key) else { throw .invalidPublicKey(kid: kid) }
            keys[kid] = raw
        }
        return try BadgeVerifier(trustedKeys: keys)
    }

    /// Verifies envelope JSON and returns the first failure of the verification order.
    /// - Parameter senderMasterKey: the sender's cross-signing master key from the SDK (`UserIdentity.masterKey()`), nil when unknown.
    public func verify(envelopeJSON: Data, sender: String, senderMasterKey: String?, now: Date = .now) -> BadgeVerificationResult {
        guard let text = String(data: envelopeJSON, encoding: .utf8) else { return .invalid(.malformed) }
        let nowSeconds = Int64(now.timeIntervalSince1970.rounded(.down))
        switch trust {
        case .keys(let keys):
            let trusted = keys.map { TrustedKey(kid: $0.key, publicKey: $0.value) }
            switch verifyBoundSenderBadge(envelopeJson: text, sender: sender, senderMasterKey: senderMasterKey, trustedKeys: trusted, nowSeconds: nowSeconds) {
            case .valid: return Self.shown(envelopeJSON, issuerName: nil)
            case .invalid(let failure): return .invalid(Self.failure(badgeFailureCode(failure: failure)))
            }
        case .registry(let registry):
            switch verifyIssuedBadge(envelopeJson: text, sender: sender, senderMasterKey: senderMasterKey, issuerRegistryJson: registry,
                                     lifeDays: UInt32(BipeRegistries.badgeLifeDays), nowSeconds: nowSeconds) {
            case .valid(let badge): return Self.shown(envelopeJSON, issuerName: badge.issuerName)
            case .invalid(let failure): return .invalid(Self.failure(badgeFailureCode(failure: failure)))
            }
        }
    }

    /// The envelope as the server sends it. Its `payload` and `sig` are the exact base64url text, so re-encoding is safe.
    public func verify(_ envelope: BadgeEnvelope, sender: String, senderMasterKey: String?, now: Date = .now) -> BadgeVerificationResult {
        guard let json = try? JSONEncoder().encode(envelope) else { return .invalid(.malformed) }
        return verify(envelopeJSON: json, sender: sender, senderMasterKey: senderMasterKey, now: now)
    }

    private static func failure(_ code: String) -> BadgeVerificationFailure {
        BadgeVerificationFailure(rawValue: code) ?? .malformed
    }

    /// The payload of an envelope that bipe-core accepted, as the app shows it. A payload the app cannot read is no
    /// seal: `malformed`, fail closed.
    private static func shown(_ envelopeJSON: Data, issuerName: String?) -> BadgeVerificationResult {
        guard let envelope = try? JSONDecoder().decode(BadgeEnvelope.self, from: envelopeJSON),
              let bytes = Data(base64URLEncoded: envelope.payload),
              var payload = try? JSONDecoder().decode(BadgePayload.self, from: bytes) else {
            return .invalid(.malformed)
        }
        payload.issuerName = issuerName
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
    /// A brand's mark on a reseller's work account (ADR 0162), bound to the reseller's master key.
    case brandMark = "brand-mark"
    /// A person proved by a scheme of any country (ADR 0272). `BadgePayload.scheme` names it.
    case person
    /// A company proved by a scheme of any country (ADR 0272). `BadgePayload.scheme` names it.
    case org

    /// What the level proves, for feature gates (ADR 0272 decision 11, bipe-shared `badgeClass`). `pix` and `govbr`
    /// are aliases of person schemes, `cnpj` of an org scheme. `institution` and `brand-mark` have no class.
    public var badgeClass: BadgeClass? {
        switch self {
        case .pix, .govbr, .person: .person
        case .cnpj, .org: .org
        case .institution, .brandMark: nil
        }
    }
}

/// The class of a badge (ADR 0272): what a feature gate reads instead of a level name.
public nonisolated enum BadgeClass: String, Sendable {
    case person
    case org
}

/// The scheme fields of a `person` or `org` badge (ADR 0272, docs/badge-attestation.md "Scheme fields").
public nonisolated struct BadgeScheme: Sendable, Equatable {
    /// The scheme ID, for example `nl.kvk`.
    public let id: String
    /// ISO 3166-1 alpha-2 country of the scheme, upper case.
    public let country: String
    /// The assurance of the scheme: `bank` or `state` for a person, `registry+control` for a company.
    public let assurance: String
    /// `org` only: the company's number in its registry. Opaque: shown as it is.
    public let orgId: String?
    /// `org` only, optional: the registered name.
    public let legalName: String?

    /// The seal comes from a scheme outside Brazil. The sheet then names the issuer and the country, and says that it
    /// is not a Brazilian seal (ADR 0272 decision 7).
    public var isForeign: Bool { country != "BR" }

    /// The country in the device's language, for example "Países Baixos". The code itself when the system has no name.
    public var countryName: String { Locale.current.localizedString(forRegionCode: country) ?? country }
}

/// How a company proved control of its CNPJ (ADR 0042).
public nonisolated enum BadgeCNPJMethod: String, Sendable, Codable {
    case ecnpj
    case partner
    case staff
}

/// The company fields of a `cnpj` badge.
public nonisolated struct BadgeCompany: Sendable, Equatable {
    public let legalName: String
    public let tradeName: String?
    public let method: BadgeCNPJMethod
    /// The Receita Federal release that last showed the CNPJ as active, YYYY-MM.
    public let checkedMonth: String
}

/// The fields of a `brand-mark` badge (ADR 0162). `name` and `cnpj` of the payload are the brand's.
public nonisolated struct BadgeBrandMark: Sendable, Equatable {
    /// The mark ID (UUID).
    public let mark: String
    /// The brand's Matrix user ID.
    public let brand: String
    /// One id of `BipeRegistries.brandMarkLabels`.
    public let label: String
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
    /// Present only for `brand-mark`.
    public let brandMark: BadgeBrandMark?
    /// Present only for `person` and `org`.
    public let scheme: BadgeScheme?
    /// `institution` only: the verified link domains (ADR 0036), lower case. Empty: no clickable link.
    public let domains: [String]
    /// The account's cross-signing master key the badge is bound to (`msk`, every level, ADR 0239).
    public let msk: String
    /// `cnpj` only, optional: the CVM register line (ADR 0077). Read leniently: a bad value is no line, never a bad badge.
    public let cvm: BipeCvmField?
    /// Issued at, Unix seconds.
    public let iat: Int64
    /// Expires at, Unix seconds.
    public let exp: Int64
    /// The issuer ID (`iss`). Absent means `bipe` (docs/badge-attestation.md "Issuer").
    public let issuer: String
    /// The issuer `name` from the signed issuer registry; nil when a flat key list checked the badge.
    public internal(set) var issuerName: String?
    
    private enum CodingKeys: String, CodingKey {
        case sub, lvl, name, cnpj, msk, iat, exp, iss
        case legalName = "legal_name"
        case tradeName = "trade_name"
        case method
        case checkedMonth = "checked_month"
        case cvm
        case domains
        case mark, brand, label
        case country, scheme, assurance
        case orgId = "org_id"
    }
    
    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sub = try container.decode(String.self, forKey: .sub)
        lvl = try container.decode(BadgeLevel.self, forKey: .lvl)
        name = try container.decode(String.self, forKey: .name)
        cnpj = try container.decodeIfPresent(String.self, forKey: .cnpj)
        iat = try container.decode(Int64.self, forKey: .iat)
        exp = try container.decode(Int64.self, forKey: .exp)
        // badge-attestation.md "Parser rules".
        guard (0...253_402_300_799).contains(iat), (0...253_402_300_799).contains(exp) else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "time out of range"))
        }
        issuer = try container.decodeIfPresent(String.self, forKey: .iss) ?? "bipe"
        issuerName = nil
        msk = try container.decode(String.self, forKey: .msk)
        guard !msk.isEmpty else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "empty msk"))
        }
        cvm = lvl == .cnpj ? ((try? container.decodeIfPresent(BipeCvmField.self, forKey: .cvm)) ?? nil) : nil
        switch lvl {
        case .cnpj:
            let legalName = try container.decode(String.self, forKey: .legalName)
            let checkedMonth = try container.decode(String.self, forKey: .checkedMonth)
            guard let cnpj, cnpj.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil, !legalName.isEmpty,
                  checkedMonth.wholeMatch(of: /[0-9]{4}-(0[1-9]|1[0-2])/) != nil else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed cnpj badge"))
            }
            company = try BadgeCompany(legalName: legalName,
                                       tradeName: container.decodeIfPresent(String.self, forKey: .tradeName),
                                       method: container.decode(BadgeCNPJMethod.self, forKey: .method),
                                       checkedMonth: checkedMonth)
            brandMark = nil
            domains = []
            scheme = nil
        case .brandMark:
            let mark = try container.decode(String.self, forKey: .mark)
            let brand = try container.decode(String.self, forKey: .brand)
            let label = try container.decode(String.self, forKey: .label)
            guard let cnpj, cnpj.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil, !mark.isEmpty, !brand.isEmpty,
                  !name.isEmpty, BipeRegistries.brandMarkLabels.contains(label) else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed brand-mark badge"))
            }
            company = nil
            brandMark = BadgeBrandMark(mark: mark, brand: brand, label: label)
            domains = []
            scheme = nil
        case .institution:
            let domains = try container.decodeIfPresent([String].self, forKey: .domains) ?? []
            guard let cnpj, cnpj.wholeMatch(of: /[0-9A-Z]{12}[0-9]{2}/) != nil,
                  domains.allSatisfy(BipeNoticeLinks.isDomain) else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed institution badge"))
            }
            company = nil
            brandMark = nil
            self.domains = domains
            scheme = nil
        case .pix, .govbr:
            company = nil
            brandMark = nil
            domains = []
            scheme = nil
        case .person, .org:
            // Parser rules for person and org (badge-attestation.md, verification step 5). bipe-core checked them first.
            let id = try container.decode(String.self, forKey: .scheme)
            let country = try container.decode(String.self, forKey: .country)
            let assurance = try container.decode(String.self, forKey: .assurance)
            let orgId = try container.decodeIfPresent(String.self, forKey: .orgId)
            let legalName = try container.decodeIfPresent(String.self, forKey: .legalName)
            guard !name.isEmpty, !assurance.isEmpty, country.wholeMatch(of: /[A-Z]{2}/) != nil,
                  id.wholeMatch(of: /[a-z]{2}\.[a-z0-9-]{1,30}/) != nil,
                  lvl == .person || (orgId.map { (1...128).contains($0.count) } ?? false),
                  legalName.map({ !$0.isEmpty }) ?? true else {
                throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "malformed scheme badge"))
            }
            scheme = BadgeScheme(id: id, country: country, assurance: assurance,
                                 orgId: lvl == .org ? orgId : nil, legalName: lvl == .org ? legalName : nil)
            company = nil
            brandMark = nil
            domains = []
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
    case masterKeyMismatch = "master_key_mismatch"
    // Steps 2, 6 to 9 and 12: only a check against the issuer registry gives these (bipe-chat/bipe#637).
    case unknownIssuer = "unknown_issuer"
    case issuerMismatch = "issuer_mismatch"
    case levelNotAllowed = "level_not_allowed"
    case unknownScheme = "unknown_scheme"
    case schemeNotAllowed = "scheme_not_allowed"
    case schemeMismatch = "scheme_mismatch"
    case lifeTooLong = "life_too_long"
    case regionMismatch = "region_mismatch"
}

// MARK: - Private

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
