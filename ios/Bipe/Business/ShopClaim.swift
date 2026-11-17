// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Security

/// ADR 0195 point 7: a claim link from the shop on the web waits here until an account is signed in, also through
/// sign-up. The token moves the browser's records to the account, then it stops working.
///
/// The link holds a token, so it waits in the keychain, on this device only and not in a backup, never in
/// UserDefaults (bipe-chat/bipe#1369). A link older than `maxAge` is dropped. Android has the same age (ShopClaims.kt).
enum ShopClaim {
    /// Seven days: long enough to finish a sign-up, short enough that a lost token does not wait for ever.
    static let maxAge: TimeInterval = 7 * 24 * 60 * 60
    private static let service = "chat.bipe.shopClaim"
    private static let account = "link"
    /// Where builds before bipe#1369 kept the link.
    private static let oldDefaultsKey = "bipe.shopClaim.link"

    private struct Stored: Codable {
        let url: String
        let savedAt: Date
    }

    /// `true` when `url` is a claim link, which then waits.
    static func keep(_ url: URL) -> Bool {
        guard BipeProfileLink.shopClaim(from: url) != nil else { return false }
        write(Stored(url: url.absoluteString, savedAt: Date()))
        return true
    }

    static func pending(now: Date = Date()) -> (handle: String, token: String)? {
        moveOldLink()
        guard let stored = read() else { return nil }
        guard now.timeIntervalSince(stored.savedAt) < maxAge else {
            clear()
            return nil
        }
        return URL(string: stored.url).flatMap(BipeProfileLink.shopClaim(from:))
    }

    static func clear() {
        SecItemDelete(query() as CFDictionary)
        UserDefaults.standard.removeObject(forKey: oldDefaultsKey)
    }

    // MARK: - Private

    private static func query() -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    /// A link from an older build moves into the keychain with the time of the move, then leaves UserDefaults.
    private static func moveOldLink() {
        guard let old = UserDefaults.standard.string(forKey: oldDefaultsKey) else { return }
        if read() == nil { write(Stored(url: old, savedAt: Date())) }
        UserDefaults.standard.removeObject(forKey: oldDefaultsKey)
    }

    private static func read() -> Stored? {
        var query = query()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return try? JSONDecoder().decode(Stored.self, from: data)
    }

    private static func write(_ stored: Stored) {
        guard let data = try? JSONEncoder().encode(stored) else { return }
        let attributes: [String: Any] = [
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            kSecValueData as String: data,
        ]
        var status = SecItemUpdate(query() as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query()
            item.merge(attributes) { _, new in new }
            item[kSecAttrSynchronizable as String] = false
            status = SecItemAdd(item as CFDictionary, nil)
        }
        if status != errSecSuccess {
            NSLog("Bipe shop claim write failed: \(status)")
        }
    }
}
