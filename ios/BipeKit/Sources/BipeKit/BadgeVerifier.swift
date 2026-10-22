// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Written from bipe-shared docs/badge-attestation.md (format v1, verification order) and ADR 0123.
// Cases: test-vectors/badges.json. Fail closed: anything but `.valid` means no badge.

import CryptoKit
import Foundation

/// Contract `BadgeEnvelope`.
public struct BipeBadgeEnvelope: Codable, Sendable, Equatable {
    public let v: Int
    public let kid: String
    /// base64url JSON payload bytes.
    public let payload: String
    /// base64url Ed25519 signature over the decoded payload bytes.
    public let sig: String

    public init(v: Int, kid: String, payload: String, sig: String) {
        self.v = v
        self.kid = kid
        self.payload = payload
        self.sig = sig
    }
}

/// The signed statement inside an envelope.
public struct BipeBadgePayload: Sendable, Equatable {
    public enum Level: String, Sendable {
        case pix
        case govbr
        case institution
        case cnpj
    }

    public let sub: String
    public let lvl: Level
    /// For `cnpj`: the trade name when the registry has one, else the legal name.
    public let name: String
    public let iat: Date
    public let exp: Date
    /// Device binding (`pix`, `govbr`, `institution`).
    public let dev: String?
    /// Account binding (`cnpj`): the cross-signing master key.
    public let msk: String?
    public let cnpj: String?
    public let legalName: String?
    public let tradeName: String?
    public let method: String?
    /// `YYYY-MM`: the Receita Federal release that last showed the CNPJ as active.
    public let checkedMonth: String?
}

/// Result ids of docs/badge-attestation.md.
public enum BipeBadgeResult: Sendable, Equatable {
    case valid(BipeBadgePayload)
    case unsupportedVersion
    case unknownKid
    case invalidSignature
    case malformed
    case expired
    case notYetValid
    case subjectMismatch
    case masterKeyMismatch
    case deviceMismatch

    public var id: String {
        switch self {
        case .valid: "valid"
        case .unsupportedVersion: "unsupported_version"
        case .unknownKid: "unknown_kid"
        case .invalidSignature: "invalid_signature"
        case .malformed: "malformed"
        case .expired: "expired"
        case .notYetValid: "not_yet_valid"
        case .subjectMismatch: "subject_mismatch"
        case .masterKeyMismatch: "master_key_mismatch"
        case .deviceMismatch: "device_mismatch"
        }
    }
}

public nonisolated enum BadgeVerifier {
    /// Verifies `envelope` in the order of docs/badge-attestation.md.
    /// - Parameters:
    ///   - sender: the Matrix user ID the badge must name.
    ///   - senderDevice: the sending device's Ed25519 key, for device-bound levels.
    ///   - senderMasterKey: the sender's cross-signing master key from the SDK (`UserIdentity.masterKey()`), for `cnpj`.
    ///   - keys: kid to raw Ed25519 public key (base64url). A release build passes ``BipeRegistries/badgeKeys`` only.
    public static func verify(_ envelope: BipeBadgeEnvelope,
                              sender: String,
                              senderDevice: String? = nil,
                              senderMasterKey: String?,
                              now: Date = Date(),
                              keys: [String: String] = BipeRegistries.badgeKeys) -> BipeBadgeResult {
        guard envelope.v == 1 else { return .unsupportedVersion }
        guard let keyString = keys[envelope.kid],
              let keyData = base64URLDecode(keyString),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData) else { return .unknownKid }
        guard let payloadData = base64URLDecode(envelope.payload),
              let signature = base64URLDecode(envelope.sig),
              key.isValidSignature(signature, for: payloadData) else { return .invalidSignature }
        guard let payload = parse(payloadData) else { return .malformed }
        if now < payload.iat {
            return .notYetValid
        }
        if now >= payload.exp {
            return .expired
        }
        guard payload.sub == sender else { return .subjectMismatch }
        switch payload.lvl {
        case .cnpj:
            guard let senderMasterKey, payload.msk == senderMasterKey else { return .masterKeyMismatch }
        case .pix, .govbr, .institution:
            guard let senderDevice, payload.dev == senderDevice else { return .deviceMismatch }
        }
        return .valid(payload)
    }

    /// Unpadded or padded base64url, also plain base64.
    public static func base64URLDecode(_ string: String) -> Data? {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder == 1 {
            return nil
        }
        if remainder > 0 {
            base64 += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: base64)
    }

    private static func parse(_ data: Data) -> BipeBadgePayload? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let sub = object["sub"] as? String, !sub.isEmpty,
              let lvlString = object["lvl"] as? String, let lvl = BipeBadgePayload.Level(rawValue: lvlString),
              let name = object["name"] as? String, !name.isEmpty,
              let iat = integer(object["iat"]), let exp = integer(object["exp"]) else { return nil }
        let dev = nonEmpty(object["dev"])
        let msk = nonEmpty(object["msk"])
        let cnpj = object["cnpj"] as? String
        let legalName = object["legal_name"] as? String
        let tradeName = nonEmpty(object["trade_name"])
        let method = object["method"] as? String
        let checkedMonth = object["checked_month"] as? String
        switch lvl {
        case .cnpj:
            guard let cnpj, matches(cnpj, "^[0-9A-Z]{12}[0-9]{2}$"),
                  let legalName, !legalName.isEmpty,
                  msk != nil,
                  let method, ["ecnpj", "partner", "staff"].contains(method),
                  let checkedMonth, matches(checkedMonth, "^[0-9]{4}-(0[1-9]|1[0-2])$") else { return nil }
        case .pix, .govbr, .institution:
            guard dev != nil else { return nil }
        }
        return BipeBadgePayload(sub: sub, lvl: lvl, name: name,
                                iat: Date(timeIntervalSince1970: TimeInterval(iat)),
                                exp: Date(timeIntervalSince1970: TimeInterval(exp)),
                                dev: dev, msk: msk, cnpj: cnpj, legalName: legalName, tradeName: tradeName,
                                method: method, checkedMonth: checkedMonth)
    }

    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double == double.rounded() else { return nil }
        return number.int64Value
    }

    private static func nonEmpty(_ value: Any?) -> String? {
        guard let string = value as? String, !string.isEmpty else { return nil }
        return string
    }

    private static func matches(_ string: String, _ pattern: String) -> Bool {
        string.range(of: pattern, options: .regularExpression) != nil
    }
}
