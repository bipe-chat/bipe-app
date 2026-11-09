// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
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

    /// `PUT /v1/me/guardians`. Replaces the list (0 to ``BipeRegistries/theftMaxGuardians`` IDs). Needs the current PIN
    /// (bipe-chat/bipe#908).
    func setGuardians(_ userIDs: [String], pin: String, accessToken: String) async throws(BipeAPIError) -> BipeGuardianState {
        try await send(method: "PUT", path: ["v1", "me", "guardians"], body: GuardiansRequest(userIds: userIDs, pin: pin), accessToken: accessToken)
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
    func theftMarks(userIDs: [String], openID: BipeOpenIDToken) async throws(BipeAPIError) -> [BadgeEnvelope] {
        let response: TheftMarksResponse = try await send(method: "POST", path: ["v1", "theft-marks"], body: TheftMarksRequest(userIds: userIDs),
                                                          accessToken: openID.accessToken, serverName: openID.matrixServerName)
        return response.marks
    }
}

private nonisolated struct GuardiansRequest: Encodable {
    let userIds: [String]
    let pin: String
}

private nonisolated struct TheftMarksRequest: Encodable {
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

/// Verifies signed theft statements (bipe-shared docs/theft-mark.md, v1) with the badge keys, through bipe-core
/// `verifyTheftMark` (ADR 0267, bipe-chat/bipe#1330). Must match bipe-shared/test-vectors/theft-marks.json.
public nonisolated struct BipeTheftMarkVerifier: Sendable {
    private let trustedKeys: [String: Data]

    /// - Parameter trustedKeys: `kid` to raw 32-byte Ed25519 public key. Release builds must not include `test-only-*` keys.
    public init(trustedKeys: [String: Data]) throws(BadgeVerifier.KeyError) {
        for (kid, key) in trustedKeys where key.count != 32 {
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

    /// Verifies envelope JSON. Any error is no mark.
    /// - Parameter subject: the account the app asked about.
    public func verify(envelopeJSON: Data, subject: String, now: Date = .now) -> BipeTheftMarkResult {
        guard let text = String(data: envelopeJSON, encoding: .utf8) else { return .invalid(.malformed) }
        let keys = trustedKeys.map { TrustedKey(kid: $0.key, publicKey: $0.value) }
        switch verifyTheftMark(envelopeJson: text, subject: subject, trustedKeys: keys, nowSeconds: Int64(now.timeIntervalSince1970.rounded(.down))) {
        case .valid(let statement):
            return .valid(BipeTheftStatement(sub: statement.sub, stolenAt: statement.stolenAt, iat: statement.iat, exp: statement.exp))
        case .invalid(let failure):
            return .invalid(BadgeVerificationFailure(rawValue: badgeFailureCode(failure: failure)) ?? .malformed)
        }
    }

    /// The envelope as the server sends it. Its `payload` and `sig` are the exact base64url text, so re-encoding is safe.
    public func verify(_ envelope: BadgeEnvelope, subject: String, now: Date = .now) -> BipeTheftMarkResult {
        guard let json = try? JSONEncoder().encode(envelope) else { return .invalid(.malformed) }
        return verify(envelopeJSON: json, subject: subject, now: now)
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
