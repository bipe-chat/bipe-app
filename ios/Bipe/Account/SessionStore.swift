// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import Security

/// Keeps the SDK session (tokens, device ID) of every account in the app's own keychain group, on this device only.
/// One item per account, keyed by Matrix ID (ADR 0086). Never logged.
enum SessionStore {
    private static let service = "chat.bipe.next.session"
    /// The single slot of builds before bipe-chat/bipe#577. `loadAll()` moves it under its Matrix ID.
    private static let legacyAccount = "current"

    private struct Stored: Codable {
        var accessToken: String
        var refreshToken: String?
        var userId: String
        var deviceId: String
        var homeserverUrl: String
        var oauthData: String?
        var nativeSlidingSync: Bool
    }

    /// The saved session of every account on this phone, by Matrix ID.
    static func loadAll() -> [String: Session] {
        migrateLegacySlot()
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [:] }
        var sessions: [String: Session] = [:]
        for item in items {
            guard let data = item[kSecValueData as String] as? Data, let session = decode(data) else { continue }
            sessions[session.userId] = session
        }
        return sessions
    }

    static func load(userId: String) -> Session? {
        read(account: userId).flatMap(decode)
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
        write(data, account: session.userId)
    }

    static func delete(userId: String) {
        delete(account: userId)
    }

    private static func migrateLegacySlot() {
        guard let data = read(account: legacyAccount) else { return }
        if let session = decode(data), read(account: session.userId) == nil {
            write(data, account: session.userId)
        }
        delete(account: legacyAccount)
    }

    private static func decode(_ data: Data) -> Session? {
        guard let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return nil }
        return Session(accessToken: stored.accessToken,
                       refreshToken: stored.refreshToken,
                       userId: stored.userId,
                       deviceId: stored.deviceId,
                       homeserverUrl: stored.homeserverUrl,
                       oauthData: stored.oauthData,
                       slidingSyncVersion: stored.nativeSlidingSync ? .native : .none)
    }

    private static func read(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private static func write(_ data: Data, account: String) {
        delete(account: account)
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

    private static func delete(account: String) {
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
        guard let session = SessionStore.load(userId: userId) else {
            throw ClientError.Generic(msg: "no saved session", details: nil)
        }
        return session
    }

    func saveSessionInKeychain(session: Session) {
        SessionStore.save(session)
    }
}
