// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation
import XCTest

/// bipe-shared test-vectors/badges.json through the released XCFramework (bipe-chat/bipe#1277 items 4 and 5).
/// core.yml runs it on arm64 macOS, x86_64 macOS (Rosetta) and the iOS simulator; the metrics line names the slice.
final class BipeCoreReleaseTests: XCTestCase {
    func testVectorsOnThisSlice() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "badges", withExtension: "json"))
        let file = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let now = try XCTUnwrap(file["now"] as? NSNumber).int64Value
        let keys = try XCTUnwrap(file["publicKeys"] as? [String: String]).map { kid, key in
            TrustedKey(kid: kid, publicKey: Self.base64URL(key))
        }
        let vectors = try XCTUnwrap(file["vectors"] as? [[String: Any]])
        XCTAssertFalse(vectors.isEmpty)
        for vector in vectors {
            let name = try XCTUnwrap(vector["name"] as? String)
            let expected = try XCTUnwrap(vector["expect"] as? String)
            let data = try JSONSerialization.data(withJSONObject: try XCTUnwrap(vector["envelope"]))
            let envelope = try XCTUnwrap(String(data: data, encoding: .utf8))
            let bound = verifyBoundSenderBadge(
                envelopeJson: envelope,
                sender: try XCTUnwrap(vector["sender"] as? String),
                senderMasterKey: vector["senderMasterKey"] as? String,
                trustedKeys: keys,
                nowSeconds: now
            )
            XCTAssertEqual(boundBadgeResultCode(result: bound), expected, name)
        }
        print("BIPE_CORE_RELEASE {\"arch\":\"\(Self.arch)\",\"vectors\":\(vectors.count)}")
    }

    private static var arch: String {
        #if arch(arm64)
            "arm64"
        #elseif arch(x86_64)
            "x86_64"
        #else
            "other"
        #endif
    }

    private static func base64URL(_ value: String) -> Data {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        return Data(base64Encoded: base64)!
    }
}
