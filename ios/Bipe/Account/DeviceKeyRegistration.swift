// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Spec account-start deviceKey (ADR 0283, bipe#1351): after each ready session, this phone gets a device key for the
/// account and registers it, then deletes the stored hidden password and keeps the account record. No screen, no text.
@MainActor
enum DeviceKeyRegistration {
    /// One run per account at a time.
    private static var running: Set<String> = []

    /// Runs on every added or replaced session. Cheap when the key is already registered for this device.
    static func run(_ session: AccountSession, api: BipeAPIClient) async {
        let userId = session.userId
        guard !running.contains(userId), let deviceId = try? session.client.deviceId() else { return }
        running.insert(userId)
        defer { running.remove(userId) }
        if BipeDeviceKey.Registration.isRegistered(userId: userId, deviceId: deviceId),
           case .found = BipeDeviceKey.lookup(userId: userId) {
            // A delete that failed before tries again.
            BipeCredentialStore().forgetPassword(for: userId)
            return
        }
        // 409 before for this device: make no new key. The phone cannot approve links until it recovers.
        guard !BipeDeviceKey.Registration.isTaken(userId: userId, deviceId: deviceId) else { return }
        // No Secure Enclave (the Simulator): no key, no registration, and the password stays.
        guard let key = BipeDeviceKey.keyMakingOne(userId: userId),
              let publicKey = BipeDeviceKey.publicKeySPKI(key) else { return }
        do throws(BipeAPIError) {
            try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.registerDeviceKey(publicKey: publicKey, accessToken: token)
            }
            BipeDeviceKey.Registration.markRegistered(userId: userId, deviceId: deviceId)
            BipeCredentialStore().forgetPassword(for: userId)
            AccountStep.log.notice("device key registered")
        } catch {
            if case .server(.deviceKeyExists, _, _) = error {
                // Another key holds this device. The password is no use to this phone any more (ADR 0283).
                AccountStep.log.error("device key: the device has another key")
                BipeDeviceKey.Registration.markTaken(userId: userId, deviceId: deviceId)
                BipeCredentialStore().forgetPassword(for: userId)
            } else {
                // Offline or an older server: the next launch tries again.
                AccountStep.log.error("device key: registration failed, retried at the next launch")
            }
        }
    }

    /// Spec link-computer-scan support gate: this phone registered its device key for the account's current device.
    static func isRegistered(_ session: AccountSession) -> Bool {
        guard let deviceId = try? session.client.deviceId(),
              BipeDeviceKey.Registration.isRegistered(userId: session.userId, deviceId: deviceId),
              case .found = BipeDeviceKey.lookup(userId: session.userId) else { return false }
        return true
    }

    /// Spec link-computer-approve: a one-time hidden password from POST /v1/me/password-once, signed with the device
    /// key. Memory only: the caller uses it once and drops it. Never logged.
    static func passwordOnce(_ session: AccountSession, api: BipeAPIClient) async throws(BipeAPIError) -> String {
        let userId = session.userId
        guard let deviceId = try? session.client.deviceId() else { throw .server(.deviceKeyMissing, reason: nil, statusCode: 409) }
        let challenge = try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> String in
            try await api.passwordOnceChallenge(accessToken: token)
        }
        let message = BipeDeviceKey.passwordOnceMessage(userId: userId, deviceId: deviceId, challenge: challenge)
        guard let signature = BipeDeviceKey.sign(message, userId: userId) else {
            throw .server(.deviceKeyMissing, reason: nil, statusCode: 409)
        }
        return try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) -> String in
            try await api.passwordOnce(deviceId: deviceId, challenge: challenge, signature: signature, accessToken: token)
        }
    }
}
