// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeAccount.swift).

import Foundation

// Device-first accounts (ADR 0057, ADR 0059). Contract: bipe-shared/api/openapi.yaml.

/// Contract: `Credentials`. Shown once by the server. The password stays on this device; the token syncs.
public struct BipeCredentials: Decodable, Sendable, Equatable, CustomStringConvertible {
    public let userId: String
    public let password: String
    public let recoveryToken: String
    
    public init(userId: String, password: String, recoveryToken: String) {
        self.userId = userId
        self.password = password
        self.recoveryToken = recoveryToken
    }
    
    /// Never prints the password or the token.
    public var description: String {
        "BipeCredentials(\(userId))"
    }
}

/// Contract: `AccountStatus`. Read when the app comes to the foreground.
public struct BipeAccountStatus: Decodable, Sendable, Equatable {
    public let userId: String
    public let pinSet: Bool
    public let pinBlocked: Bool
    public let pinTriesLeft: Int
    /// `false` for a beta account that still signs in with its own password.
    public let deviceFirst: Bool
    public let recoveryTokenSet: Bool
    /// The verified recovery email.
    public let email: String?
    /// An address waiting for its link.
    public let emailPending: String?
    /// Waiting ones, and ones that ended in the last 7 days. Newest first.
    public let recoveries: [BipeRecovery]
    /// The last legal version this account accepted (ADR 0113). Absent for accounts made before ADR 0113.
    public let termsVersion: String?
    /// Personal accounts one phone may hold (ADR 0086). Absent from an older server.
    public let maxAccounts: Int?
    /// ADR 0087. Absent from an older server: personal.
    public let accountType: BipeAccountType?
}

/// Contract: `Recovery`.
public struct BipeRecovery: Decodable, Sendable, Equatable, Identifiable {
    public let id: String
    public let method: BipeRecoveryMethod
    public let purpose: BipeRecoveryPurpose
    public let state: BipeRecoveryState
    public let createdAt: Date
    /// When the delay ends.
    public let readyAt: Date?
    public let endedAt: Date?
    
    public init(id: String, method: BipeRecoveryMethod, purpose: BipeRecoveryPurpose, state: BipeRecoveryState,
                createdAt: Date, readyAt: Date?, endedAt: Date?) {
        self.id = id
        self.method = method
        self.purpose = purpose
        self.state = state
        self.createdAt = createdAt
        self.readyAt = readyAt
        self.endedAt = endedAt
    }
}

/// Contract: `RecoveryStarted`. The new device keeps it until the claim completes.
public struct BipeRecoveryStarted: Codable, Sendable, Equatable, CustomStringConvertible {
    public let recoveryId: String
    /// Shown once. Proves this device when it claims.
    public let claimSecret: String
    public let purpose: BipeRecoveryPurpose
    public let readyAt: Date
    
    public init(recoveryId: String, claimSecret: String, purpose: BipeRecoveryPurpose, readyAt: Date) {
        self.recoveryId = recoveryId
        self.claimSecret = claimSecret
        self.purpose = purpose
        self.readyAt = readyAt
    }
    
    /// Never prints the claim secret.
    public var description: String {
        "BipeRecoveryStarted(\(recoveryId), \(purpose.rawValue))"
    }
}

/// Contract: `RecoveryClaim`.
public struct BipeRecoveryClaim: Decodable, Sendable, CustomStringConvertible {
    public let state: BipeRecoveryState
    public let purpose: BipeRecoveryPurpose
    public let readyAt: Date?
    /// Access, completed by this call only.
    public let userId: String?
    /// Access, completed by this call only. Shown once.
    public let password: String?
    /// Access, completed by this call only. Shown once.
    public let recoveryToken: String?
    
    public var credentials: BipeCredentials? {
        guard let userId, let password, let recoveryToken else { return nil }
        return BipeCredentials(userId: userId, password: password, recoveryToken: recoveryToken)
    }
    
    /// Never prints the password or the token.
    public var description: String {
        "BipeRecoveryClaim(\(state.rawValue), \(purpose.rawValue))"
    }
}

/// Contract: `Recovery.method`.
public enum BipeRecoveryMethod: String, Codable, Sendable {
    case token, email, guardian, cpf
    
    public init(from decoder: Decoder) throws {
        // An unknown value from a newer server must not fail the account state.
        self = try Self(rawValue: decoder.singleValueContainer().decode(String.self)) ?? .email
    }
}

/// Contract: `Recovery.purpose`.
public enum BipeRecoveryPurpose: String, Codable, Sendable {
    case access
    case pinReset = "pin_reset"
    
    public init(from decoder: Decoder) throws {
        self = try Self(rawValue: decoder.singleValueContainer().decode(String.self)) ?? .access
    }
}

/// Contract: `Recovery.state`.
public enum BipeRecoveryState: String, Codable, Sendable {
    case waiting, completed, cancelled, expired
    
    public init(from decoder: Decoder) throws {
        self = try Self(rawValue: decoder.singleValueContainer().decode(String.self)) ?? .expired
    }
}

public extension BipeRecovery {
    /// The alert text for an old device, or `nil` when this recovery needs no alert.
    /// A waiting recovery can be cancelled; a completed token recovery is a notice only.
    var alertMessage: String? {
        let date = (readyAt ?? createdAt).formatted(date: .abbreviated, time: .shortened)
        switch (state, purpose, method) {
        case (.waiting, .pinReset, _): return BipeStrings.recoveryAlertPinReset(date)
        case (.waiting, .access, .guardian): return BipeStrings.recoveryAlertGuardian(date)
        case (.waiting, .access, .cpf): return BipeStrings.recoveryAlertCpf(date)
        case (.waiting, .access, _): return BipeStrings.recoveryAlertEmail(date)
        case (.completed, .access, .token): return BipeStrings.recoveryAlertToken
        default: return nil
        }
    }
    
    var canBeCancelled: Bool {
        state == .waiting
    }
}

/// Recovery links in the email: `https://bipe.chat/recuperar/#<token>`. The token sits in the fragment, so it stays out of server logs.
public nonisolated enum BipeRecoveryLink {
    /// The link token, or `nil` when the URL is not a recovery link.
    public static func token(from url: URL) -> String? {
        guard url.scheme == "https", BipeProfileLink.isBipeLinkHost(url) else { return nil }
        let components = url.pathComponents.filter { $0 != "/" }
        guard components == ["recuperar"], let fragment = url.fragment(percentEncoded: false) else { return nil }
        let token = fragment.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1...128).contains(token.count), token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else { return nil }
        return token
    }
}

/// A recovery link token. Prints redacted, because routes and actions get logged.
public nonisolated struct BipeRecoveryLinkToken: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let value: String
    
    public init?(url: URL) {
        guard let value = BipeRecoveryLink.token(from: url) else { return nil }
        self.value = value
    }
    
    public var description: String {
        "BipeRecoveryLinkToken(redacted)"
    }
    
    public var debugDescription: String {
        description
    }
}
