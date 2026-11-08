// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import CryptoKit
import Foundation
import MatrixRustSDK
import XCTest

/// bipe-shared test-vectors/badges.json through the packaged XCFramework (bipe-chat/bipe#1172).
final class BipeCoreHarnessTests: XCTestCase {
    func testVectorsMetricsAndMatrixSDK() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "badges", withExtension: "json"))
        let file = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let now = try XCTUnwrap(file["now"] as? NSNumber).int64Value
        let keys = try XCTUnwrap(file["publicKeys"] as? [String: String]).map { kid, key in
            TrustedKey(kid: kid, publicKey: base64URL(key))
        }
        let vectors = try XCTUnwrap(file["vectors"] as? [[String: Any]])
        let bench = try XCTUnwrap(vectors.first { $0["name"] as? String == "cnpj: valid" })
        let benchEnvelope = try text(bench["envelope"])
        let benchSender = try XCTUnwrap(bench["sender"] as? String)
        let benchKey = bench["senderMasterKey"] as? String

        // The first call in this process.
        let coldStart = DispatchTime.now().uptimeNanoseconds
        let first = verifyBoundSenderBadge(envelopeJson: benchEnvelope, sender: benchSender, senderMasterKey: benchKey, trustedKeys: keys, nowSeconds: now)
        let coldMicros = Double(DispatchTime.now().uptimeNanoseconds - coldStart) / 1000
        XCTAssertEqual(boundBadgeResultCode(result: first), "valid")

        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let expected = try XCTUnwrap(vector["expect"] as? String)
            let envelope = try text(vector["envelope"])
            let bound = verifyBoundSenderBadge(
                envelopeJson: envelope,
                sender: try XCTUnwrap(vector["sender"] as? String),
                senderMasterKey: vector["senderMasterKey"] as? String,
                trustedKeys: keys,
                nowSeconds: now
            )
            XCTAssertEqual(boundBadgeResultCode(result: bound), expected, name)
            let statement = verifyPublicIssuerStatement(envelopeJson: envelope, homeserver: "bipe.net.br", trustedKeys: keys, nowSeconds: now)
            let publicExpected = ["subject_mismatch", "master_key_mismatch"].contains(expected) ? "valid" : expected
            XCTAssertEqual(publicStatementResultCode(result: statement), publicExpected, name)
        }

        // Both static Rust libraries are in this binary. Call the SDK, then the core again.
        XCTAssertFalse(MatrixRustSDK.sdkGitSha().isEmpty)
        var samples: [Double] = []
        for _ in 0..<1000 {
            let start = DispatchTime.now().uptimeNanoseconds
            _ = verifyBoundSenderBadge(envelopeJson: benchEnvelope, sender: benchSender, senderMasterKey: benchKey, trustedKeys: keys, nowSeconds: now)
            samples.append(Double(DispatchTime.now().uptimeNanoseconds - start) / 1000)
        }
        samples.sort()
        print("BIPE_CORE_METRICS {\"vectors\":\(vectors.count),\"cold_us\":\(coldMicros),\"p95_us\":\(samples[949])}")
    }

    /// core/harness/ed25519-cases.json (bipe-chat/bipe#1277 item 7): bipe-core accepts no signature that CryptoKit
    /// refuses. The payload of a case is not JSON, so `malformed` means that the signature check passed.
    func testSignatureEncodingsAgainstCryptoKit() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "ed25519-cases", withExtension: "json"))
        let file = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try XCTUnwrap(file["cases"] as? [[String: String]])
        XCTAssertFalse(cases.isEmpty)
        var rows: [String] = []
        for (index, item) in cases.enumerated() {
            let message = try hex(item["message"]), key = try hex(item["publicKey"]), signature = try hex(item["signature"])
            let envelope = "{\"v\":1,\"kid\":\"case\",\"payload\":\"\(base64URLEncoded(message))\",\"sig\":\"\(base64URLEncoded(signature))\"}"
            let result = verifyPublicIssuerStatement(
                envelopeJson: envelope, homeserver: "bipe.net.br", trustedKeys: [TrustedKey(kid: "case", publicKey: key)], nowSeconds: 1_800_000_000
            )
            let core = publicStatementResultCode(result: result) == "malformed"
            let kit = (try? Curve25519.Signing.PublicKey(rawRepresentation: key))?.isValidSignature(signature, for: message) ?? false
            rows.append("\(index):\(core ? "V" : "X")\(kit ? "V" : "X")")
            if core { XCTAssertTrue(kit, "case \(index): bipe-core accepts a signature that CryptoKit refuses") }
        }
        print("BIPE_ED25519_CASES core/CryptoKit \(rows.joined(separator: " "))")
    }

    private func hex(_ value: String?) throws -> Data {
        let text = Array(try XCTUnwrap(value).utf8)
        return Data(stride(from: 0, to: text.count, by: 2).map { UInt8(String(decoding: text[$0..<$0 + 2], as: UTF8.self), radix: 16)! })
    }

    private func base64URLEncoded(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    private func text(_ envelope: Any?) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(envelope))
        return try XCTUnwrap(String(data: data, encoding: .utf8))
    }

    private func base64URL(_ value: String) -> Data {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        return Data(base64Encoded: base64)!
    }
}
