// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Security
import Testing

/// bipe-shared test-vectors/device-keys.json (ADR 0283): the signed message bytes, the public key format and the
/// signature format. Test-only public keys; no test makes a Secure Enclave key.
struct DeviceKeyTests {
    @Test
    func messageBytesMatchTheVector() throws {
        let file = try SharedVectors.object("device-keys.json")
        #expect(file["tag"] as? String == BipeDeviceKey.messageTag)
        let message = try #require(file["message"] as? [String: Any])
        let bytes = BipeDeviceKey.passwordOnceMessage(userId: try #require(message["user_id"] as? String),
                                                      deviceId: try #require(message["device_id"] as? String),
                                                      challenge: try #require(message["challenge"] as? String))
        #expect(bytes == Data(try #require(message["message_utf8"] as? String).utf8))
    }

    @Test
    func publicKeyIsPrefixPlusX963() throws {
        let file = try SharedVectors.object("device-keys.json")
        let prefix = try #require(file["spki_prefix_hex"] as? String)
        #expect(BipeDeviceKey.spkiPrefix.map { String(format: "%02x", $0) }.joined() == prefix)
        for key in try SharedVectors.array("device-keys.json", key: "keys") {
            let point = try #require(Data(base64Encoded: try #require(key["x963_base64"] as? String)))
            #expect(BipeDeviceKey.spki(x963: point)?.base64EncodedString() == key["spki_base64"] as? String)
        }
        #expect(BipeDeviceKey.spki(x963: Data(repeating: 4, count: 64)) == nil)
    }

    /// The Security verify call with the same algorithm the phone signs with (ecdsaSignatureMessageX962SHA256).
    @Test
    func signatureFormatMatchesTheVectors() throws {
        let keys = try SharedVectors.array("device-keys.json", key: "keys")
        let vectors = try SharedVectors.array("device-keys.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let name = vector["name"] as? String ?? ""
            let spki = try #require(vector["public_key"] as? String)
            let x963 = try #require(keys.first { $0["spki_base64"] as? String == spki }?["x963_base64"] as? String)
            let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
                                             kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
                                             kSecAttrKeySizeInBits as String: 256]
            let publicKey = try #require(SecKeyCreateWithData(try #require(Data(base64Encoded: x963)) as CFData,
                                                              attributes as CFDictionary, nil))
            let message = BipeDeviceKey.passwordOnceMessage(userId: try #require(vector["user_id"] as? String),
                                                            deviceId: try #require(vector["device_id"] as? String),
                                                            challenge: try #require(vector["challenge"] as? String))
            let signature = try #require(Data(base64Encoded: try #require(vector["signature"] as? String)))
            let valid = SecKeyVerifySignature(publicKey, .ecdsaSignatureMessageX962SHA256, message as CFData,
                                              signature as CFData, nil)
            #expect(valid == (vector["expect"] as? Bool), "device key \(name)")
        }
    }
}
