// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import Testing

/// The current verifier's time, for comparison with bipe-core (bipe-chat/bipe#1172). Runs only with BIPE_BENCH set.
@Suite(.enabled(if: ProcessInfo.processInfo.environment["BIPE_BENCH"] != nil))
struct BadgeVerifierBenchTests {
    @Test
    func coldStartAndP95() throws {
        let file = try SharedVectors.object("badges.json")
        let now = try Date(timeIntervalSince1970: #require(file["now"] as? Double))
        let keys = try #require(file["publicKeys"] as? [String: String])
        let vectors = try #require(file["vectors"] as? [[String: Any]])
        let bench = try #require(vectors.first { $0["name"] as? String == "cnpj: valid" })
        let envelope = try JSONSerialization.data(withJSONObject: #require(bench["envelope"]))
        let sender = try #require(bench["sender"] as? String)
        let masterKey = bench["senderMasterKey"] as? String

        func key(_ value: String) -> Data? {
            var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
            return Data(base64Encoded: base64)
        }

        let coldStart = DispatchTime.now().uptimeNanoseconds
        let verifier = try BadgeVerifier(trustedKeys: keys.mapValues { try #require(key($0)) })
        let first = verifier.verify(envelopeJSON: envelope, sender: sender, senderMasterKey: masterKey, now: now)
        let coldMicros = Double(DispatchTime.now().uptimeNanoseconds - coldStart) / 1000
        guard case .valid = first else {
            Issue.record("bench vector")
            return
        }

        var samples: [Double] = []
        for _ in 0..<1000 {
            let start = DispatchTime.now().uptimeNanoseconds
            _ = verifier.verify(envelopeJSON: envelope, sender: sender, senderMasterKey: masterKey, now: now)
            samples.append(Double(DispatchTime.now().uptimeNanoseconds - start) / 1000)
        }
        samples.sort()
        print("BIPE_BASELINE_METRICS {\"cold_us\":\(coldMicros),\"p95_us\":\(samples[949])}")
    }
}
