// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The app lock store of spec app-lock (bipe-shared product/screens/app-lock.json), written from the spec.

import CommonCrypto
import Foundation
import Security

/// The app lock PIN and the biometric unlock flag (spec app-lock, ADR 0137). They live in the keychain on this device
/// only, never synced, never sent. The PIN is kept as a salted PBKDF2 hash and checked on the device; a wrong app lock
/// PIN never counts against the account.
///
/// The lock lives in the app-group keychain group, which survives the App Store transfer (bipe-chat/bipe#531). A lock
/// saved before that sits in the default group: reads fall back to it, so the app never opens without its lock, and
/// BipeKeychainMove copies it.
public struct BipeAppLock: Sendable {
    /// The service the app-group move copies (BipeKeychainMove).
    public static let defaultService = "chat.bipe.next.applock"

    private let service: String
    private let accessGroup: String?

    public init(service: String = BipeAppLock.defaultService,
                accessGroup: String? = Bundle.main.object(forInfoDictionaryKey: "BipeOwnedKeychainGroup") as? String) {
        self.service = service
        self.accessGroup = accessGroup.flatMap { !$0.isEmpty && !$0.contains("$(") ? $0 : nil }
    }

    private struct Stored: Codable {
        var salt: Data
        var hash: Data
        var biometricUnlock: Bool
    }

    /// The lock is on while an app lock PIN is set. Off by default (ADR 0137).
    public var isOn: Bool { read() != nil }

    public var biometricUnlock: Bool { read()?.biometricUnlock ?? false }

    /// Sets the app lock PIN. Keeps the biometric flag when the lock was already on.
    @discardableResult
    public func setPIN(_ pin: String) -> Bool {
        var salt = Data(count: 16)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard status == errSecSuccess, let hash = Self.hash(pin, salt: salt) else { return false }
        return write(Stored(salt: salt, hash: hash, biometricUnlock: read()?.biometricUnlock ?? false))
    }

    /// Checks a PIN on the device.
    public func verifyPIN(_ pin: String) -> Bool {
        guard let stored = read(), let hash = Self.hash(pin, salt: stored.salt) else { return false }
        // Constant-time compare.
        guard hash.count == stored.hash.count else { return false }
        return zip(hash, stored.hash).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }

    public func setBiometricUnlock(_ isOn: Bool) {
        guard var stored = read() else { return }
        stored.biometricUnlock = isOn
        write(stored)
    }

    /// Turns the lock off: removes the app lock PIN and biometric unlock, in every group, so no older copy locks again.
    public func remove() {
        SecItemDelete(query(group: nil) as CFDictionary)
    }

    // MARK: - Private

    private static func hash(_ pin: String, salt: Data) -> Data? {
        let password = Array(pin.utf8)
        var derived = Data(count: 32)
        let status = derived.withUnsafeMutableBytes { derivedBytes in
            salt.withUnsafeBytes { saltBytes in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2),
                                     password.map { Int8(bitPattern: $0) }, password.count,
                                     saltBytes.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 100_000,
                                     derivedBytes.bindMemory(to: UInt8.self).baseAddress, 32)
            }
        }
        return Int(status) == kCCSuccess ? derived : nil
    }

    /// `group` nil: every group the app reads.
    private func query(group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "lock",
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    /// The app-group copy first, then any other copy: a lock in either group keeps the app locked.
    private func read() -> Stored? {
        for group in [accessGroup, nil] {
            var query = query(group: group)
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data,
               let stored = try? JSONDecoder().decode(Stored.self, from: data) {
                return stored
            }
        }
        return nil
    }

    /// Writes the app-group copy. Without an app group (unexpanded Info.plist) it keeps the default group.
    @discardableResult
    private func write(_ stored: Stored) -> Bool {
        guard let data = try? JSONEncoder().encode(stored) else { return false }
        SecItemDelete(query(group: accessGroup) as CFDictionary)
        var item = query(group: accessGroup)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        item[kSecValueData as String] = data
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }
}
