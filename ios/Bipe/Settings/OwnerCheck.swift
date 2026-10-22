// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import LocalAuthentication

/// The phone's own owner checks: biometrics, or biometrics and the phone's passcode (Apple LocalAuthentication).
enum OwnerCheck {
    /// What the phone offers (spec app-lock, biometricKind).
    enum BiometricKind {
        case none
        case faceId
        case touchId
    }

    static var biometricKind: BiometricKind {
        let context = LAContext()
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) else { return .none }
        switch context.biometryType {
        case .faceID: return .faceId
        case .touchID: return .touchId
        default: return .none
        }
    }

    /// "Face ID" or "Touch ID", for the strings that name the biometrics.
    static var biometricWord: String {
        switch biometricKind {
        case .touchId: "Touch ID"
        default: "Face ID"
        }
    }

    /// True when the phone has a passcode, so the owner check can run.
    static var canCheckOwner: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Biometrics only. False on a fail or cancel.
    static func biometrics(reason: String) async -> Bool {
        await evaluate(.deviceOwnerAuthenticationWithBiometrics, reason: reason)
    }

    /// Biometrics or the phone's passcode. False on a fail or cancel.
    static func owner(reason: String) async -> Bool {
        await evaluate(.deviceOwnerAuthentication, reason: reason)
    }

    private static func evaluate(_ policy: LAPolicy, reason: String) async -> Bool {
        let context = LAContext()
        guard context.canEvaluatePolicy(policy, error: nil) else { return false }
        return (try? await context.evaluatePolicy(policy, localizedReason: reason)) ?? false
    }
}
