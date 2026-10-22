// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Security

/// The hidden MAS password of a device account (ADR 0057), read from the keychain.
///
/// The item layout is the one of BipeKit `BipeCredentialStore`: generic password, service
/// `chat.bipe.account.password`, `kSecAttrAccount` = Matrix ID (ADR 0086). This type only reads.
/// It never logs or shows the password.
struct DeviceCredential: CustomStringConvertible {
    let userId: String
    let password: String
    /// When the item was stored. The oldest credential is the phone's primary account (spec account-start notes).
    let created: Date

    var description: String { "DeviceCredential(\(userId))" }

    private static let service = "chat.bipe.account.password"

    /// Every account's password on this phone, oldest first: the app's own group (an account made in this app) and
    /// the fork's group (ADR 0147). The app's own item wins when both hold the same account.
    static func all() -> [DeviceCredential] {
        var byUser: [String: DeviceCredential] = [:]
        for credential in read(accessGroup: AppConfig.forkKeychainGroup) + read(accessGroup: nil) {
            byUser[credential.userId] = credential
        }
        return byUser.values.sorted { $0.created < $1.created }
    }

    private static func read(accessGroup: String?) -> [DeviceCredential] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecReturnData as String: true,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        if let accessGroup {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let userId = item[kSecAttrAccount as String] as? String,
                  let data = item[kSecValueData as String] as? Data,
                  let password = String(data: data, encoding: .utf8) else { return nil }
            let created = item[kSecAttrCreationDate as String] as? Date
                ?? item[kSecAttrModificationDate as String] as? Date
                ?? .distantFuture
            return DeviceCredential(userId: userId, password: password, created: created)
        }
    }
}
