// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Company seals (ADR 0042, ADR 0123): what the app shows for a company account's badge.
// Written from bipe-shared product/screens/company-seal.json and docs/badge-attestation.md.

import Foundation

/// The seal of an account. Only `.verified` shows a seal; anything else shows none (fail closed).
public enum BipeCompanySealState: Sendable, Equatable {
    /// A valid `cnpj` badge bound to the account's master key.
    case verified(BipeBadgePayload)
    /// `GET /v1/badges` answered 410.
    case revoked(BipeBadgeGoneReason)
    /// The badge no longer binds to the account's master key (for example after a key reset).
    case suspended
    /// No badge, or one that does not verify.
    case noSeal

    /// The notice for a seal that is no longer valid, else `nil`.
    public var notice: String? {
        switch self {
        case .revoked(.notActive), .revoked(.notFound): BipeStrings.badgeCompanyRevokedNotActive
        case .revoked: BipeStrings.badgeCompanyRevoked
        case .suspended: BipeStrings.badgeCompanySuspended
        case .verified, .noSeal: nil
        }
    }

    public var isVerified: Bool {
        if case .verified = self { true } else { false }
    }
}

public nonisolated enum BipeCompanySeals {
    /// The seal state from a badge lookup. `masterKey` is the account's cross-signing master key from the SDK,
    /// `nil` when the app does not know it.
    public static func state(of lookup: BipeBadgeLookup, userID: String, masterKey: String?,
                             now: Date = Date(), keys: [String: String] = BipeRegistries.badgeKeys) -> BipeCompanySealState {
        switch lookup {
        case .noBadge:
            return .noSeal
        case .gone(let reason):
            return .revoked(reason)
        case .envelope(let envelope):
            switch BadgeVerifier.verify(envelope, sender: userID, senderMasterKey: masterKey, now: now, keys: keys) {
            case .valid(let payload) where payload.lvl == .cnpj:
                return .verified(payload)
            case .masterKeyMismatch:
                return .suspended
            default:
                return .noSeal
            }
        }
    }

    /// `checked_month` (`YYYY-MM`) as the first day of that month, UTC.
    public static func checkedDate(_ payload: BipeBadgePayload) -> Date? {
        guard let month = payload.checkedMonth else { return nil }
        let parts = month.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC") ?? .current
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: 1))
    }
}
