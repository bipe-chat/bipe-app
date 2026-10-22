// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Security

/// The hidden MAS password of the device account (ADR 0057), read from the keychain.
///
/// The item layout is the one of BipeKit `BipeCredentialStore`: generic password, service
/// `chat.bipe.account.password`, `kSecAttrAccount` = Matrix ID (ADR 0086). This type only reads.
/// It never logs or shows the password.
struct DeviceCredential: CustomStringConvertible {
    let userId: String
    let password: String

    var description: String { "DeviceCredential(\(userId))" }

    private static let service = "chat.bipe.account.password"

    /// The newest password: first the fork's group (ADR 0147), then the app's own default group.
    static func newest() -> DeviceCredential? {
        read(accessGroup: AppConfig.forkKeychainGroup) ?? read(accessGroup: nil)
    }

    private static func read(accessGroup: String?) -> DeviceCredential? {
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
              let items = result as? [[String: Any]] else { return nil }
        let newest = items.max { lhs, rhs in
            (lhs[kSecAttrModificationDate as String] as? Date ?? .distantPast)
                < (rhs[kSecAttrModificationDate as String] as? Date ?? .distantPast)
        }
        guard let newest,
              let userId = newest[kSecAttrAccount as String] as? String,
              let data = newest[kSecValueData as String] as? Data,
              let password = String(data: data, encoding: .utf8) else { return nil }
        return DeviceCredential(userId: userId, password: password)
    }
}
