// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation
import SwiftUI
import UIKit

/// bipe-chat/bipe#1277 item 5: the iOS twin of core/release/android/bench. Runs bipe-shared test-vectors/badges.json
/// through the release XCFramework on this iPhone, then times 1000 verifications of the vector "cnpj: valid". The
/// screen and stdout (`xcrun devicectl device process launch --console`) show the result line and the verdict against
/// the budget of 5 ms p95. A wrong vector result shows FAILED. A diagnostic screen in English: no product text.
@main
struct BenchApp: App {
    @State private var result = Bench.running

    var body: some Scene {
        WindowGroup {
            ScrollView {
                Text(result)
                    .font(.body.monospaced())
                    .textSelection(.enabled)
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .task {
                let report = await Task.detached(priority: .userInitiated) { Bench.report() }.value
                result = report
            }
        }
    }
}

enum Bench {
    static let running = "bipe-core: running"
    static let budgetMs = 5.0

    static func report() -> String {
        let body: String
        do {
            let line = try run()
            let p95 = try metric("p95_us", in: line) / 1000
            let verdict = p95 <= budgetMs ? "WITHIN BUDGET" : "OVER BUDGET"
            body = String(format: "%@: p95 %.3f ms (budget %.0f ms)\n\n%@", verdict, p95, budgetMs, line)
        } catch {
            body = "FAILED: \(error)"
        }
        let device = UIDevice.current
        let report = "\(body)\n\n\(model()), \(device.systemName) \(device.systemVersion), \(arch), bipe-core \(version)"
        print("BipeCoreBench: \(report.replacingOccurrences(of: "\n", with: " "))")
        fflush(stdout)
        return report
    }

    struct Failure: Error, CustomStringConvertible { let description: String }

    /// Runs the vectors and returns the metrics line. Throws on the first wrong result.
    static func run() throws -> String {
        guard let url = Bundle.main.url(forResource: "badges", withExtension: "json") else { throw Failure(description: "no badges.json") }
        guard let file = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              let now = (file["now"] as? NSNumber)?.int64Value,
              let publicKeys = file["publicKeys"] as? [String: String],
              let vectors = file["vectors"] as? [[String: Any]] else { throw Failure(description: "badges.json shape") }
        let keys = publicKeys.map { TrustedKey(kid: $0.key, publicKey: base64URL($0.value)) }
        guard let bench = vectors.first(where: { $0["name"] as? String == "cnpj: valid" }) else { throw Failure(description: "no bench vector") }
        let benchEnvelope = try envelope(bench)
        let benchSender = bench["sender"] as? String ?? ""
        let benchMaster = bench["senderMasterKey"] as? String

        // The first call in this process, with the library load.
        let coldStart = DispatchTime.now().uptimeNanoseconds
        let first = verifyBoundSenderBadge(envelopeJson: benchEnvelope, sender: benchSender, senderMasterKey: benchMaster, trustedKeys: keys, nowSeconds: now)
        let coldMicros = Double(DispatchTime.now().uptimeNanoseconds - coldStart) / 1000
        guard boundBadgeResultCode(result: first) == "valid" else { throw Failure(description: "bench vector") }

        for vector in vectors {
            let name = vector["name"] as? String ?? ""
            let expected = vector["expect"] as? String ?? ""
            let json = try envelope(vector)
            let bound = boundBadgeResultCode(result: verifyBoundSenderBadge(
                envelopeJson: json, sender: vector["sender"] as? String ?? "", senderMasterKey: vector["senderMasterKey"] as? String,
                trustedKeys: keys, nowSeconds: now))
            guard bound == expected else { throw Failure(description: "bound \(name): \(bound), expected \(expected)") }
            let publicExpected = expected == "subject_mismatch" || expected == "master_key_mismatch" ? "valid" : expected
            let statement = publicStatementResultCode(result: verifyPublicIssuerStatement(
                envelopeJson: json, homeserver: "bipe.net.br", trustedKeys: keys, nowSeconds: now))
            guard statement == publicExpected else { throw Failure(description: "public \(name): \(statement), expected \(publicExpected)") }
        }

        let samples = (0..<1000).map { _ -> Double in
            let start = DispatchTime.now().uptimeNanoseconds
            _ = verifyBoundSenderBadge(envelopeJson: benchEnvelope, sender: benchSender, senderMasterKey: benchMaster, trustedKeys: keys, nowSeconds: now)
            return Double(DispatchTime.now().uptimeNanoseconds - start) / 1000
        }.sorted()
        return "BIPE_CORE_METRICS {\"vectors\":\(vectors.count),\"cold_us\":\(coldMicros),\"p95_us\":\(samples[949])}"
    }

    private static func envelope(_ vector: [String: Any]) throws -> String {
        guard let value = vector["envelope"] else { throw Failure(description: "no envelope") }
        let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        return String(decoding: data, as: UTF8.self)
    }

    private static func metric(_ key: String, in line: String) throws -> Double {
        guard let range = line.range(of: "\"\(key)\":"),
              let value = Double(line[range.upperBound...].prefix { "0123456789.eE+-".contains($0) }) else {
            throw Failure(description: "no \(key)")
        }
        return value
    }

    private static func base64URL(_ value: String) -> Data {
        var base64 = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        return Data(base64Encoded: base64) ?? Data()
    }

    private static func model() -> String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { String(decoding: $0.prefix { $0 != 0 }, as: UTF8.self) }
    }

    private static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }

    private static var arch: String {
        #if arch(arm64)
            "arm64"
        #else
            "other"
        #endif
    }
}
