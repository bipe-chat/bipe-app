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
public struct BipeAppLock: Sendable {
    private let service: String

    public init(service: String = "chat.bipe.next.applock") {
        self.service = service
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

    /// Turns the lock off: removes the app lock PIN and biometric unlock.
    public func remove() {
        SecItemDelete(query() as CFDictionary)
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

    private func query() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: "lock",
        ]
    }

    private func read() -> Stored? {
        var query = query()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Stored.self, from: data)
    }

    @discardableResult
    private func write(_ stored: Stored) -> Bool {
        guard let data = try? JSONEncoder().encode(stored) else { return false }
        SecItemDelete(query() as CFDictionary)
        var item = query()
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        item[kSecValueData as String] = data
        return SecItemAdd(item as CFDictionary, nil) == errSecSuccess
    }
}
