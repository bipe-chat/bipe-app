// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import LocalAuthentication

/// The biometric check (or the device passcode) before an action that hands over the account or its history:
/// approving a device, cancelling a recovery, replacing the recovery key (specs device-verification, recovery-alert,
/// recovery-key-save).
enum OwnerCheck {
    /// `true` when the owner passed. A phone without a passcode passes: there is nothing to check against.
    static func confirm(reason: String) async -> Bool {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return error?.code == LAError.passcodeNotSet.rawValue
        }
        return (try? await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}
