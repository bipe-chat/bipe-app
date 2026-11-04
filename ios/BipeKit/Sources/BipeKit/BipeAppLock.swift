// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// The app lock store of spec app-lock (bipe-shared product/screens/app-lock.json), written from the spec.

import CommonCrypto
import Foundation
import Security

/// The app lock PIN and the biometric unlock flag (spec app-lock, ADR 0137). They live in the keychain on this device
/// only, never synced, never sent. The PIN is kept as a salted PBKDF2 hash and checked on the device; a wrong app lock
/// PIN never counts against the account, but it counts against the local try limit (BipeAppLockTries).
///
/// The lock lives in the app-group keychain group, which survives the App Store transfer (bipe-chat/bipe#531). A lock
/// saved before that sits in the default group: reads fall back to it, so the app never opens without its lock, and
/// BipeKeychainMove copies it.
///
/// A lock record that cannot be read keeps the lock on (bipe-chat/bipe#777): only a record that is not there means off.
public struct BipeAppLock: Sendable {
    /// The service the app-group move copies (BipeKeychainMove).
    public static let defaultService = "chat.bipe.next.applock"

    /// The result of a PIN check.
    public enum Check: Equatable, Sendable {
        case match
        case wrong
        /// Too many wrong tries: no check until this time.
        case wait(until: Date)
    }

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
        /// Wrong tries in a row. Nil in a record from before the try limit.
        var failures: Int?
        var lastFailure: Date?
    }

    private enum Record {
        case none
        case stored(Stored)
        /// The keychain or the record failed: the lock stays on.
        case unreadable
    }

    /// The lock is on while an app lock PIN is set, or while the lock record cannot be read. Off by default (ADR 0137).
    public var isOn: Bool {
        if case .none = read() { return false }
        return true
    }

    public var biometricUnlock: Bool {
        if case let .stored(stored) = read() { return stored.biometricUnlock }
        return false
    }

    /// Sets the app lock PIN. Keeps the biometric flag when the lock was already on. Clears the wrong tries.
    @discardableResult
    public func setPIN(_ pin: String) -> Bool {
        var salt = Data(count: 16)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 16, $0.baseAddress!) }
        guard status == errSecSuccess, let hash = Self.hash(pin, salt: salt) else { return false }
        return write(Stored(salt: salt, hash: hash, biometricUnlock: biometricUnlock))
    }

    /// Checks a PIN on the device, with the try limit. A wrong PIN is counted before the answer, so closing the app
    /// does not undo it. An unreadable record never matches.
    public func checkPIN(_ pin: String, now: Date = Date()) -> Check {
        guard case var .stored(stored) = read() else { return .wrong }
        if let until = waitUntil(&stored, now: now) { return .wait(until: until) }
        guard let hash = Self.hash(pin, salt: stored.salt) else { return .wrong }
        // Constant-time compare.
        let match = hash.count == stored.hash.count && zip(hash, stored.hash).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
        if match {
            if stored.failures ?? 0 > 0 { clearFailures(&stored) }
            return .match
        }
        stored.failures = (stored.failures ?? 0) + 1
        stored.lastFailure = now
        write(stored)
        return BipeAppLockTries.waitEnd(failures: stored.failures ?? 0, lastFailure: now, now: now).map { Check.wait(until: $0) } ?? .wrong
    }

    /// The end of the current wait, or nil when a PIN can be typed.
    public func waitUntil(now: Date = Date()) -> Date? {
        guard case var .stored(stored) = read() else { return nil }
        return waitUntil(&stored, now: now)
    }

    /// After a biometric unlock: the owner is back, so the wrong tries start again from zero.
    public func clearFailures() {
        guard case var .stored(stored) = read(), stored.failures ?? 0 > 0 else { return }
        clearFailures(&stored)
    }

    public func setBiometricUnlock(_ isOn: Bool) {
        guard case var .stored(stored) = read() else { return }
        stored.biometricUnlock = isOn
        write(stored)
    }

    /// Turns the lock off: removes the app lock PIN and biometric unlock, in every group, so no older copy locks again.
    public func remove() {
        SecItemDelete(query(group: nil) as CFDictionary)
    }

    // MARK: - Private

    /// A clock set back before the last wrong try restarts the wait from now, so it cannot shorten the wait.
    private func waitUntil(_ stored: inout Stored, now: Date) -> Date? {
        guard let failures = stored.failures, failures > 0, var last = stored.lastFailure else { return nil }
        if last > now {
            last = now
            stored.lastFailure = now
            write(stored)
        }
        return BipeAppLockTries.waitEnd(failures: failures, lastFailure: last, now: now)
    }

    private func clearFailures(_ stored: inout Stored) {
        stored.failures = nil
        stored.lastFailure = nil
        write(stored)
    }

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

    /// The app-group copy first, then any other copy: a lock in either group keeps the app locked. Only "not found" in
    /// every group means no lock; any other keychain error (the phone is locked, the keychain failed) or a record
    /// that does not decode keeps the lock on.
    private func read() -> Record {
        for group in [accessGroup, nil] {
            var query = query(group: group)
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var result: AnyObject?
            let status = SecItemCopyMatching(query as CFDictionary, &result)
            if status == errSecItemNotFound { continue }
            guard status == errSecSuccess, let data = result as? Data,
                  let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return .unreadable }
            return .stored(stored)
        }
        return .none
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

/// The app lock try limit (bipe-chat/bipe#777, spec app-lock typeDigit): the first wrong PINs are free, then each
/// wrong PIN starts a wait that grows. The count lives in the lock record, so closing the app does not reset it.
/// A match or a biometric unlock clears it. Android has the same values (BipeAppLockTries.kt).
public enum BipeAppLockTries {
    /// Wrong PINs in a row before the first wait.
    public static let freeTries = 5
    /// The wait after wrong PIN freeTries, freeTries + 1, ...; the last value repeats.
    public static let waits: [TimeInterval] = [30, 60, 5 * 60, 15 * 60, 60 * 60]

    /// The wait after this many wrong PINs in a row, or nil while tries are free.
    public static func wait(failures: Int) -> TimeInterval? {
        guard failures >= freeTries else { return nil }
        return waits[min(failures - freeTries, waits.count - 1)]
    }

    /// The end of the wait, or nil when it is over or there is none.
    public static func waitEnd(failures: Int, lastFailure: Date, now: Date) -> Date? {
        guard let wait = wait(failures: failures) else { return nil }
        let end = lastFailure.addingTimeInterval(wait)
        return end > now ? end : nil
    }
}
