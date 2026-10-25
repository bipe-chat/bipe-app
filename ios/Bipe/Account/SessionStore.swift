// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import Security

/// Keeps the SDK session (tokens, device ID) of every account in the app-group keychain group, on this device only.
/// One item per account, keyed by Matrix ID (ADR 0086). Never logged. The notification service extension reads and
/// writes it too. Also compiled into the extension.
///
/// The app-group group has no Team ID, so a session survives the App Store transfer (bipe-chat/bipe#531). Sessions
/// saved before that sit in the default group (the Team-ID group): reads fall back to it, BipeKeychainMove copies them,
/// and a sign-out deletes every copy.
enum SessionStore {
    /// The service the app-group move copies (BipeKeychainMove).
    static let service = "chat.bipe.next.session"
    private static let ownGroup = AppConfig.appGroup
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
        var sessions: [String: Session] = [:]
        // The app-group copy wins over an older copy in the default group.
        for group in [ownGroup, nil] as [String?] {
            var query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecReturnData as String: true,
                kSecReturnAttributes as String: true,
                kSecMatchLimit as String: kSecMatchLimitAll,
            ]
            if let group { query[kSecAttrAccessGroup as String] = group }
            var result: AnyObject?
            guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
                  let items = result as? [[String: Any]] else { continue }
            for item in items {
                guard let data = item[kSecValueData as String] as? Data, let session = decode(data),
                      sessions[session.userId] == nil else { continue }
                sessions[session.userId] = session
            }
        }
        return sessions
    }

    static func load(userId: String) -> Session? {
        read(account: userId).flatMap(decode)
    }

    /// A push must name its immutable owner. Never use a mutable current-account preference.
    static func loadForPush(userId: String) -> Session? {
        guard let session = load(userId: userId), session.userId == userId else { return nil }
        return session
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

    /// The app-group copy first, then any other group the app reads (the Team-ID group before the move).
    private static func read(account: String) -> Data? {
        for group in [ownGroup, nil] as [String?] {
            var query: [String: Any] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: service,
                kSecAttrAccount as String: account,
                kSecReturnData as String: true,
                kSecMatchLimit as String: kSecMatchLimitOne,
            ]
            if let group { query[kSecAttrAccessGroup as String] = group }
            var result: AnyObject?
            if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data {
                return data
            }
        }
        return nil
    }

    /// Replaces the app-group copy only. An older copy in the Team-ID group stays until sign-out; reads prefer this one.
    private static func write(_ data: Data, account: String) {
        SecItemDelete(query(account: account, group: ownGroup) as CFDictionary)
        var item = query(account: account, group: ownGroup)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        item[kSecValueData as String] = data
        let status = SecItemAdd(item as CFDictionary, nil)
        if status != errSecSuccess {
            NSLog("Bipe session write failed: \(status)")
        }
    }

    /// Sign-out: every copy, in every group the app reads, so an older copy never signs the account in again.
    private static func delete(account: String) {
        SecItemDelete(query(account: account, group: nil) as CFDictionary)
    }

    private static func query(account: String, group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
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
