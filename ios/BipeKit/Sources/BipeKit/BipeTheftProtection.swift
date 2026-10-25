// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation
import Security

// Theft protection (ADR 0044, ADR 0051, bipe-chat/bipe#600, #268). Contract: GuardianState, Guardian,
// GuardedAccount and TheftMark in bipe-shared api/openapi.yaml. Statement: bipe-shared docs/theft-mark.md.

/// The answer of `GET /v1/me/guardians` and `PUT /v1/me/guardians`.
public nonisolated struct BipeGuardianState: Decodable, Equatable, Sendable {
    /// The caller's guardians, in the server's order. At most ``BipeRegistries/theftMaxGuardians``.
    public let guardians: [BipeGuardian]
    /// The accounts that chose the caller as a guardian.
    public let guarding: [BipeGuardedAccount]
    /// The caller's most recent mark, after it was cleared. `nil` when the account was never marked.
    public let lastMark: BipeTheftMark?

    public init(guardians: [BipeGuardian], guarding: [BipeGuardedAccount], lastMark: BipeTheftMark?) {
        self.guardians = guardians
        self.guarding = guarding
        self.lastMark = lastMark
    }
}

public nonisolated struct BipeGuardian: Decodable, Equatable, Sendable {
    public let userId: String
    public let addedAt: Date

    public init(userId: String, addedAt: Date) {
        self.userId = userId
        self.addedAt = addedAt
    }
}

public nonisolated struct BipeGuardedAccount: Decodable, Equatable, Sendable {
    public let userId: String
    /// Set while a mark is on.
    public let mark: BipeTheftMark?

    public init(userId: String, mark: BipeTheftMark?) {
        self.userId = userId
        self.mark = mark
    }
}

public nonisolated struct BipeTheftMark: Decodable, Equatable, Sendable {
    public let userId: String
    public let stolenAt: Date
    /// The guardian who set the mark. The owner sees it (ADR 0044).
    public let markedBy: String
    public let clearedAt: Date?

    public init(userId: String, stolenAt: Date, markedBy: String, clearedAt: Date?) {
        self.userId = userId
        self.stolenAt = stolenAt
        self.markedBy = markedBy
        self.clearedAt = clearedAt
    }
}

public extension BipeAPIClient {
    /// `GET /v1/me/guardians`.
    func guardians(accessToken: String) async throws(BipeAPIError) -> BipeGuardianState {
        try await send(method: "GET", path: ["v1", "me", "guardians"], body: Empty?.none, accessToken: accessToken)
    }

    /// `PUT /v1/me/guardians`. Replaces the list (0 to ``BipeRegistries/theftMaxGuardians`` IDs).
    func setGuardians(_ userIDs: [String], accessToken: String) async throws(BipeAPIError) -> BipeGuardianState {
        try await send(method: "PUT", path: ["v1", "me", "guardians"], body: GuardiansRequest(userIds: userIDs), accessToken: accessToken)
    }

    /// `PUT /v1/me/phone`. Records this phone's ID for the account (bipe-chat/bipe#268).
    func reportPhone(phoneID: String, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "PUT", path: ["v1", "me", "phone"], body: PhoneRequest(phoneId: phoneID), accessToken: accessToken)
    }

    /// `POST /v1/theft/{userId}/mark`. Only a guardian of the account; else `not_found`.
    func markStolen(userID: String, accessToken: String) async throws(BipeAPIError) -> BipeTheftMark {
        try await send(method: "POST", path: ["v1", "theft", userID, "mark"], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/theft/{userId}/clear`. Only a guardian of the account; else `not_found`.
    func clearTheftMark(userID: String, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "POST", path: ["v1", "theft", userID, "clear"], body: Empty?.none, accessToken: accessToken)
    }

    /// `POST /v1/theft-marks`. At most 100 IDs. The statements are not verified here: see ``BipeTheftMarkVerifier``.
    func theftMarks(userIDs: [String], accessToken: String) async throws(BipeAPIError) -> [BadgeEnvelope] {
        let response: TheftMarksResponse = try await send(method: "POST", path: ["v1", "theft-marks"],
                                                          body: GuardiansRequest(userIds: userIDs), accessToken: accessToken)
        return response.marks
    }
}

private nonisolated struct GuardiansRequest: Encodable {
    let userIds: [String]
}

private nonisolated struct PhoneRequest: Encodable {
    let phoneId: String
}

private nonisolated struct TheftMarksResponse: Decodable {
    let marks: [BadgeEnvelope]
}

// MARK: - Statement

/// A verified theft statement (docs/theft-mark.md, payload).
public nonisolated struct BipeTheftStatement: Equatable, Sendable {
    /// The marked account.
    public let sub: String
    /// When a guardian set the mark, Unix seconds.
    public let stolenAt: Int64
    public let iat: Int64
    public let exp: Int64

    public var stolenDate: Date { Date(timeIntervalSince1970: TimeInterval(stolenAt)) }
    public var expiryDate: Date { Date(timeIntervalSince1970: TimeInterval(exp)) }

    /// Messages from the account sent at or after this time get bipe_theft_message_marker.
    public var markerStart: Date {
        stolenDate.addingTimeInterval(-TimeInterval(BipeRegistries.theftMarkedMessageMinutes * 60))
    }
}

public nonisolated enum BipeTheftMarkResult: Sendable, Equatable {
    case valid(BipeTheftStatement)
    /// The codes are the badge codes (docs/theft-mark.md, verification order).
    case invalid(BadgeVerificationFailure)
}

/// Verifies signed theft statements (bipe-shared docs/theft-mark.md, v1) with the badge keys.
/// Must match bipe-shared/test-vectors/theft-marks.json.
public nonisolated struct BipeTheftMarkVerifier: Sendable {
    private let trustedKeys: [String: Data]

    /// - Parameter trustedKeys: `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
    public init(trustedKeys: [String: Data]) throws(BadgeVerifier.KeyError) {
        for (kid, key) in trustedKeys where (try? Curve25519.Signing.PublicKey(rawRepresentation: key)) == nil {
            throw .invalidPublicKey(kid: kid)
        }
        self.trustedKeys = trustedKeys
    }

    /// The badge trust list from bipe-shared `registries/badge-keys.json` (ADR 0123).
    public static func bipeTrusted() throws(BadgeVerifier.KeyError) -> BipeTheftMarkVerifier {
        var keys: [String: Data] = [:]
        for (kid, key) in BipeRegistries.badgeKeys {
            guard let raw = Data(base64URLEncoded: key) else { throw .invalidPublicKey(kid: kid) }
            keys[kid] = raw
        }
        return try BipeTheftMarkVerifier(trustedKeys: keys)
    }

    /// Decodes and verifies envelope JSON. Undecodable JSON is `malformed`.
    public func verify(envelopeJSON: Data, subject: String, now: Date = .now) -> BipeTheftMarkResult {
        guard let probe = try? JSONDecoder().decode(TheftVersionProbe.self, from: envelopeJSON) else {
            return .invalid(.malformed)
        }
        guard probe.v == BadgeEnvelope.supportedVersion else {
            return .invalid(.unsupportedVersion)
        }
        guard let envelope = try? JSONDecoder().decode(BadgeEnvelope.self, from: envelopeJSON) else {
            return .invalid(.malformed)
        }
        return verify(envelope, subject: subject, now: now)
    }

    /// Runs the documented checks in order and returns the first failure.
    /// - Parameter subject: the account the app asked about.
    public func verify(_ envelope: BadgeEnvelope, subject: String, now: Date = .now) -> BipeTheftMarkResult {
        guard envelope.v == BadgeEnvelope.supportedVersion else {
            return .invalid(.unsupportedVersion)
        }
        guard let keyData = trustedKeys[envelope.kid],
              let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData) else {
            return .invalid(.unknownKID)
        }
        guard let payloadBytes = Data(base64URLEncoded: envelope.payload),
              let signature = Data(base64URLEncoded: envelope.sig) else {
            return .invalid(.malformed)
        }
        // Verify the exact bytes. Never re-serialise the JSON before this check.
        guard publicKey.isValidSignature(signature, for: payloadBytes) else {
            return .invalid(.invalidSignature)
        }
        guard let payload = try? JSONDecoder().decode(TheftPayload.self, from: payloadBytes), payload.typ == "theft" else {
            return .invalid(.malformed)
        }
        let nowSeconds = now.timeIntervalSince1970
        if nowSeconds >= Double(payload.exp) {
            return .invalid(.expired)
        }
        if nowSeconds < Double(payload.iat) {
            return .invalid(.notYetValid)
        }
        guard payload.sub == subject else {
            return .invalid(.subjectMismatch)
        }
        return .valid(BipeTheftStatement(sub: payload.sub, stolenAt: payload.stolenAt, iat: payload.iat, exp: payload.exp))
    }

    /// The valid statement for `subject` among `envelopes`, else `nil`. Any error is no mark.
    public func statement(for subject: String, in envelopes: [BadgeEnvelope], now: Date = .now) -> BipeTheftStatement? {
        for envelope in envelopes {
            if case .valid(let statement) = verify(envelope, subject: subject, now: now) {
                return statement
            }
        }
        return nil
    }
}

private nonisolated struct TheftVersionProbe: Decodable {
    let v: Int
}

/// Integers only: a JSON number with a fraction does not decode as Int64, so it is `malformed`.
private nonisolated struct TheftPayload: Decodable {
    let typ: String
    let sub: String
    let stolenAt: Int64
    let iat: Int64
    let exp: Int64

    private enum CodingKeys: String, CodingKey {
        case typ, sub, iat, exp
        case stolenAt = "stolen_at"
    }
}

// MARK: - Phone ID

/// The random per-install phone ID of `PUT /v1/me/phone` (bipe-chat/bipe#268). Never derived from hardware.
public nonisolated enum BipePhoneID {
    static let key = "bipe.theft.phoneId"

    /// The kept ID, or a new one (22 URL-safe characters from 16 random bytes) that is kept from now on.
    public static func current(in defaults: UserDefaults) -> String {
        if let kept = defaults.string(forKey: key), isValid(kept) {
            return kept
        }
        let fresh = make()
        defaults.set(fresh, forKey: key)
        return fresh
    }

    /// 16 to 64 URL-safe characters (contract `phone_id`).
    public static func isValid(_ id: String) -> Bool {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        return (16...64).contains(id.count) && id.allSatisfy(allowed.contains)
    }

    static func make() -> String {
        var bytes = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            fatalError("SecRandomCopyBytes failed")
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
