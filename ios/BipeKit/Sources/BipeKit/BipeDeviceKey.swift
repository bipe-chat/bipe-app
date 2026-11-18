// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import CryptoKit
import Foundation
import Security

/// The device key of one account on this phone (ADR 0283, bipe#1351, spec account-start deviceKey).
///
/// - A Secure Enclave P-256 key, one per account, tagged with the Matrix ID. The private key never leaves the Secure
///   Enclave: it is not exportable, never synced and not in backups. Accessible after the first unlock, this device only.
/// - It lives in the app-group keychain group (`BipeOwnedKeychainGroup`), never a Team-ID group (bipe-chat/bipe#531).
/// - No Secure Enclave (the Simulator): no key and no software fallback. The phone then cannot approve links.
/// - The key signs the password-once message (contract `POST /v1/me/password-once`, test-vectors/device-keys.json).
public nonisolated enum BipeDeviceKey {
    /// The first line of the signed message. Contract and test vectors name it `tag`.
    public static let messageTag = "bipe-password-once:v1"

    /// SubjectPublicKeyInfo DER header of a P-256 key: SEQUENCE, the id-ecPublicKey and prime256v1 OIDs, BIT STRING.
    /// The 65-byte X9.63 point follows it.
    public static let spkiPrefix = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    ])

    /// The UTF-8 bytes the device key signs: tag, user ID, device ID and challenge on four lines, no final newline.
    public static func passwordOnceMessage(userId: String, deviceId: String, challenge: String) -> Data {
        Data([messageTag, userId, deviceId, challenge].joined(separator: "\n").utf8)
    }

    /// SubjectPublicKeyInfo DER of an uncompressed P-256 point (`SecKeyCopyExternalRepresentation`). `nil` for any
    /// other input.
    public static func spki(x963 point: Data) -> Data? {
        guard point.count == 65, point.first == 0x04 else { return nil }
        return spkiPrefix + point
    }

    /// What the keychain says about the key of one account.
    public enum Lookup {
        case found(SecKey)
        case none
        /// The keychain cannot answer now (for example before the first unlock). Make no new key.
        case unavailable
    }

    /// True on a phone with a Secure Enclave and an own keychain group. The Simulator has none.
    public static var isAvailable: Bool {
        SecureEnclave.isAvailable && accessGroup != nil
    }

    /// The account's key in the keychain.
    public static func lookup(userId: String) -> Lookup {
        guard let group = accessGroup else { return .unavailable }
        var query = baseQuery(userId: userId, group: group)
        query[kSecReturnRef as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return .none }
        guard status == errSecSuccess, let result, CFGetTypeID(result) == SecKeyGetTypeID() else { return .unavailable }
        return .found(unsafeDowncast(result, to: SecKey.self))
    }

    /// The account's key; makes one in the Secure Enclave when the keychain has none. `nil` when there is no Secure
    /// Enclave, the keychain cannot answer, or the Secure Enclave refused.
    public static func keyMakingOne(userId: String) -> SecKey? {
        guard isAvailable, let group = accessGroup else { return nil }
        switch lookup(userId: userId) {
        case .found(let key): return key
        case .unavailable: return nil
        case .none: break
        }
        // privateKeyUsage: signing needs no user presence. The owner check runs before the app asks to sign.
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                                           .privateKeyUsage, nil) else { return nil }
        var privateAttributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag(userId: userId),
            kSecAttrAccessControl as String: access,
            kSecAttrAccessGroup as String: group,
        ]
        #if os(macOS)
        privateAttributes[kSecUseDataProtectionKeychain as String] = true
        #endif
        // A Secure Enclave key cannot sync or go into a backup: the private key stays in the Secure Enclave.
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: privateAttributes,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            NSLog("Bipe device key: the Secure Enclave made no key")
            return nil
        }
        return key
    }

    /// Base64 SubjectPublicKeyInfo DER of the key's public key (contract `POST /v1/me/device-key` `public_key`).
    public static func publicKeySPKI(_ key: SecKey) -> String? {
        guard let publicKey = SecKeyCopyPublicKey(key),
              let point = SecKeyCopyExternalRepresentation(publicKey, nil) as Data?,
              let spki = spki(x963: point) else { return nil }
        return spki.base64EncodedString()
    }

    /// Base64 DER ECDSA P-256 SHA-256 signature of `message` with the account's key. `nil` when the account has no key.
    public static func sign(_ message: Data, userId: String) -> String? {
        guard case .found(let key) = lookup(userId: userId) else { return nil }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, message as CFData, &error) as Data? else {
            NSLog("Bipe device key: signing failed")
            return nil
        }
        return signature.base64EncodedString()
    }

    /// Sign-out (spec sign-out): the account's key and its registration go from this phone.
    public static func delete(userId: String) {
        Registration.forget(userId: userId)
        guard let group = accessGroup else { return }
        let status = SecItemDelete(baseQuery(userId: userId, group: group) as CFDictionary)
        if status != errSecSuccess, status != errSecItemNotFound {
            NSLog("Bipe device key delete failed: \(status)")
        }
    }

    // MARK: - Registration

    /// What POST /v1/me/device-key answered for the account on this device ID. Local only; never a secret.
    public enum Registration {
        /// 204: the server holds this phone's key for that device.
        public static func isRegistered(userId: String, deviceId: String) -> Bool {
            UserDefaults.standard.string(forKey: registeredKey(userId)) == deviceId
        }

        public static func markRegistered(userId: String, deviceId: String) {
            UserDefaults.standard.set(deviceId, forKey: registeredKey(userId))
            UserDefaults.standard.removeObject(forKey: takenKey(userId))
        }

        /// 409 device_key_exists: the device has another key. No new key until a recovery gives a new device.
        public static func isTaken(userId: String, deviceId: String) -> Bool {
            UserDefaults.standard.string(forKey: takenKey(userId)) == deviceId
        }

        public static func markTaken(userId: String, deviceId: String) {
            UserDefaults.standard.set(deviceId, forKey: takenKey(userId))
        }

        public static func forget(userId: String) {
            UserDefaults.standard.removeObject(forKey: registeredKey(userId))
            UserDefaults.standard.removeObject(forKey: takenKey(userId))
        }

        private static func registeredKey(_ userId: String) -> String { "bipe.deviceKey.registered.\(userId)" }
        private static func takenKey(_ userId: String) -> String { "bipe.deviceKey.taken.\(userId)" }
    }

    // MARK: - Private

    /// The app's own keychain group from Info.plist (project.yml). A missing or unexpanded group means no key.
    private static var accessGroup: String? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "BipeOwnedKeychainGroup") as? String,
              !group.isEmpty, !group.contains("$(") else { return nil }
        return group
    }

    private static func tag(userId: String) -> Data {
        Data("chat.bipe.device-key.\(userId)".utf8)
    }

    private static func baseQuery(userId: String, group: String) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrApplicationTag as String: tag(userId: userId),
            kSecAttrAccessGroup as String: group,
        ]
        #if os(macOS)
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }
}
