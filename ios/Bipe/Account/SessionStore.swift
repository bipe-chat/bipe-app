// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import Security

/// Keeps the SDK session (tokens, device ID) in the app's own keychain group, on this device only.
/// Never logged.
enum SessionStore {
    private static let service = "chat.bipe.next.session"
    private static let account = "current"

    private struct Stored: Codable {
        var accessToken: String
        var refreshToken: String?
        var userId: String
        var deviceId: String
        var homeserverUrl: String
        var oauthData: String?
        var nativeSlidingSync: Bool
    }

    static func load() -> Session? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return nil }
        return Session(accessToken: stored.accessToken,
                       refreshToken: stored.refreshToken,
                       userId: stored.userId,
                       deviceId: stored.deviceId,
                       homeserverUrl: stored.homeserverUrl,
                       oauthData: stored.oauthData,
                       slidingSyncVersion: stored.nativeSlidingSync ? .native : .none)
    }

    static func save(_ session: Session) {
        let stored = Stored(accessToken: session.accessToken,
                            refreshToken: session.refreshToken,
                            userId: session.userId,
                            deviceId: session.deviceId,
                            homeserverUrl: session.homeserverUrl,
                            oauthData: session.oauthData,
                            nativeSlidingSync: session.slidingSyncVersion == .native)
        guard let data = try? JSONEncoder().encode(stored) else { return }
        delete()
        let item: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        if status != errSecSuccess {
            NSLog("Bipe session write failed: \(status)")
        }
    }

    static func delete() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}

/// Saves the session again when the SDK refreshes the tokens.
final class SessionSaver: ClientSessionDelegate {
    func retrieveSessionFromKeychain(userId: String) throws -> Session {
        guard let session = SessionStore.load(), session.userId == userId else {
            throw ClientError.Generic(msg: "no saved session", details: nil)
        }
        return session
    }

    func saveSessionInKeychain(session: Session) {
        SessionStore.save(session)
    }
}
