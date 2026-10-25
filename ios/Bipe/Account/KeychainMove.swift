// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// Keeps every account on this iPhone through the App Store transfer (bipe-chat/bipe#531,
/// bipe/docs/team-transfer-recovery.md section 3): copies Bipe's keychain items into the app-group keychain group,
/// then tells bipe-api, once per account and device, so MZ can measure the share before the transfer.
enum KeychainMove {
    /// Every keychain service the app writes, sign-out markers first.
    static let services = BipeCredentialStore.keychainServices
        + [SessionStore.service, BipeAppLock.defaultService]
        + BipeInviteStore.keychainServices

    /// At launch, before any keychain read. Copies only; never deletes. Cheap after the first full run.
    static func run() {
        let result = BipeKeychainMove(services: services).run()
        if !result.done {
            NSLog("Bipe keychain move not finished; it runs again at the next launch")
        }
    }

    /// POST /v1/me/keychain-move for this account on this device, once the move is done. Retries on a later open
    /// when it fails. Never sends a credential: the server reads the device from the token.
    @MainActor
    static func report(_ session: AccountSession, api: BipeAPIClient) async {
        guard BipeKeychainMove(services: services).isDone,
              let deviceId = try? session.client.deviceId() else { return }
        let key = "bipe.keychainMove.reported.\(session.userId).\(deviceId)"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        do {
            try await BipeAPIClient.withTokenRetry(accessToken: { session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                try await api.recordKeychainMove(accessToken: token)
            }
            UserDefaults.standard.set(true, forKey: key)
        } catch {
            // Offline or an older server: the next open tries again.
        }
    }
}
