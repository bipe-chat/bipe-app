// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// bipe-shared test-vectors/cvm-lines.json (ADR 0077, bipe-chat/bipe#238).
struct CvmLinesTests {
    @Test
    func sharedVectors() throws {
        let vectors = try SharedVectors.array("cvm-lines.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let name = vector["name"] as? String ?? ""
            var field: BipeCvmField?
            if let raw = vector["cvm"] {
                // The payload decoder reads the field leniently; a value that is not an object is no field.
                let data = try JSONSerialization.data(withJSONObject: ["cvm": raw], options: [.fragmentsAllowed])
                field = (try? JSONDecoder().decode([String: BipeCvmField].self, from: data))?["cvm"]
            }
            let result = BipeCvmLines.lines(field)
            let expected = try #require(vector["lines"] as? [[String: Any]])
            #expect(result.lines.map(\.key.rawValue) == expected.map { $0["key"] as? String ?? "" }, "keys \(name)")
            #expect(result.lines.map(\.arg) == expected.map { $0["arg"] as? String }, "args \(name)")
            #expect(result.danger == (vector["danger"] as? Bool), "danger \(name)")
        }
    }
}
