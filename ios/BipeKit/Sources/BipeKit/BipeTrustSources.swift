// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The source adapter of the separate trust facts (ADR 0074, bipe-shared docs/trust-facts.md) for a person's profile.
/// It fills BipeTrustInput only from authenticated sources: the SDK's cross-signing state of the person and the
/// company seal that BadgeVerifier checked. Names, handles and profile fields never reach it.
public enum BipeTrustSources {
    /// The person's identity as the SDK reports it (Encryption.userIdentity).
    public struct Identity: Equatable, Sendable {
        /// UserIdentity.masterKey: the key every fact binds to.
        public var masterKey: String?
        /// UserIdentity.isVerified: the user confirmed this identity (in-person QR or emoji check).
        public var isVerified: Bool
        /// UserIdentity.hasVerificationViolation: the identity changed after the user confirmed it.
        public var hasVerificationViolation: Bool

        public init(masterKey: String?, isVerified: Bool, hasVerificationViolation: Bool) {
            self.masterKey = masterKey
            self.isVerified = isVerified
            self.hasVerificationViolation = hasVerificationViolation
        }
    }

    /// The company seal after BadgeVerifier (BipeCompanySealState), reduced to what the facts need.
    public enum Seal: Equatable, Sendable {
        case none
        /// A valid cnpj badge bound to the person's current master key, with its verified name.
        case verified(name: String)
        /// The person had a seal and it no longer verifies (for example after a master key reset).
        case suspended
    }

    /// The SDK gives states, not times. The evaluator needs a proof strictly after a change, so the adapter writes
    /// the order the SDK reports: a change at 1, a current proof at 2. The SDK withdraws isVerified on an identity
    /// change, so a confirmation that it still reports is current.
    static let changeTime: Double = 1
    static let proofTime: Double = 2

    /// Input for BipeTrustFacts.evaluate. identity nil (not read, or no cross-signing identity): lifecycle unknown.
    /// Device changes are not reported here: the SDK has no complete device history (integration gap, bipe-chat/bipe#227).
    public static func profileInput(userId: String, identity: Identity?, seal: Seal) -> BipeTrustInput {
        guard let identity, let key = identity.masterKey, !key.isEmpty else {
            return BipeTrustInput(accountId: userId, lifecycleKnown: false)
        }
        var input = BipeTrustInput(accountId: userId, deviceKey: key, lifecycleKnown: true,
                                   identityChangedAt: identity.hasVerificationViolation ? changeTime : nil)
        switch seal {
        case .verified(let name):
            input.badgeStatus = "valid"
            input.badgeAccountId = userId
            input.badgeDeviceKey = key
            input.badgeName = name
            input.badgeIssuedAt = proofTime
        case .suspended:
            input.badgeStatus = "device_mismatch"
        case .none:
            break
        }
        if identity.isVerified && !identity.hasVerificationViolation {
            input.confirmationMethod = "in_person"
            input.confirmationAccountId = userId
            input.confirmationDeviceKey = key
            input.confirmedAt = proofTime
        }
        return input
    }

    /// The facts for a profile.
    public static func profileFacts(userId: String, identity: Identity?, seal: Seal) -> BipeTrustFacts {
        BipeTrustFacts.evaluate(profileInput(userId: userId, identity: identity, seal: seal))
    }
}
