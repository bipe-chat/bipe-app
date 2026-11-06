// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import Security

/// The Mac has its own local Keychain service. It never reads the phone's groups or synced credentials.
protocol MacKeychain: Sendable {
    func read(_ account: String) throws -> Data?
    func write(_ data: Data, account: String) throws
    func remove(_ account: String) throws
}

struct SystemMacKeychain: MacKeychain {
    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "chat.bipe.app.mac.session",
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    func read(_ account: String) throws -> Data? {
        var query = query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &value)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = value as? Data else { throw MacVaultError.keychain }
        return data
    }

    func write(_ data: Data, account: String) throws {
        let query = query(account)
        let attributes = [kSecValueData as String: data]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            status = SecItemAdd(item as CFDictionary, nil)
            if status == errSecDuplicateItem { status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary) }
        }
        guard status == errSecSuccess else { throw MacVaultError.keychain }
    }

    func remove(_ account: String) throws {
        let status = SecItemDelete(query(account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw MacVaultError.keychain }
    }
}

enum MacVaultError: Error { case keychain, invalidRecord, missingKey, missingStore }

/// Tokens and the database encryption key only enter the Keychain. No credentials enter UserDefaults or logs.
struct MacSessionVault: Sendable {
    let keychain: any MacKeychain

    struct Record: Codable {
        let folder: String
        let accessToken: String
        let refreshToken: String?
        let userId: String
        let deviceId: String
        let homeserverUrl: String
        let oauthData: String?

        var session: Session {
            Session(accessToken: accessToken, refreshToken: refreshToken, userId: userId, deviceId: deviceId,
                    homeserverUrl: homeserverUrl, oauthData: oauthData, slidingSyncVersion: .native)
        }
    }

    func load() throws -> Record? {
        guard let data = try keychain.read("current") else { return nil }
        let record = try JSONDecoder().decode(Record.self, from: data)
        guard UUID(uuidString: record.folder) != nil else { throw MacVaultError.invalidRecord }
        return record
    }

    func save(_ session: Session, folder: String) throws {
        guard UUID(uuidString: folder) != nil else { throw MacVaultError.invalidRecord }
        let record = Record(folder: folder, accessToken: session.accessToken, refreshToken: session.refreshToken,
                            userId: session.userId, deviceId: session.deviceId, homeserverUrl: session.homeserverUrl,
                            oauthData: session.oauthData)
        try keychain.write(JSONEncoder().encode(record), account: "current")
    }

    func clear() throws { try keychain.remove("current") }

    func storeKey(create: Bool) throws -> Data {
        if let key = try keychain.read("database-key") {
            guard key.count == 32 else { throw MacVaultError.missingKey }
            return key
        }
        guard create else { throw MacVaultError.missingKey }
        var key = Data(count: 32)
        let status = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw MacVaultError.keychain }
        try keychain.write(key, account: "database-key")
        return key
    }
}

/// QR login does not persist its tokens until approval and secret transfer finish.
final class MacSessionDelegate: ClientSessionDelegate, @unchecked Sendable {
    private let vault: MacSessionVault
    private let folder: String
    private let lock = NSLock()
    private var active: Bool
    private let onFailure: @Sendable () -> Void

    init(vault: MacSessionVault, folder: String, active: Bool, onFailure: @escaping @Sendable () -> Void) {
        self.vault = vault
        self.folder = folder
        self.active = active
        self.onFailure = onFailure
    }

    func activate() { lock.withLock { active = true } }
    func deactivate() { lock.withLock { active = false } }

    func retrieveSessionFromKeychain(userId: String) throws -> Session {
        guard let record = try vault.load(), record.userId == userId, record.folder == folder else {
            throw MacVaultError.invalidRecord
        }
        return record.session
    }

    func saveSessionInKeychain(session: Session) {
        let failed = lock.withLock {
            guard active else { return false }
            do { try vault.save(session, folder: folder); return false } catch { return true }
        }
        if failed { onFailure() }
    }
}
