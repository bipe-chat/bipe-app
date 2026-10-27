// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct MessageTextTests {
    @Test func sharedFormattingCases() throws {
        for vector in try SharedVectors.array("message-formatting.json", key: "cases") {
            let name = try #require(vector["name"] as? String)
            let body = try #require(vector["body"] as? String)
            let parsed = BipeMessageText(body: body, html: vector["html"] as? String)
            #expect(parsed.text == vector["text"] as? String, "\(name) text")
            #expect(Set(parsed.runs.compactMap(\.link)) == Set(vector["links"] as? [String] ?? []), "\(name) links")
            #expect(parsed.runs.filter(\.bold).map(\.text).joined() == vector["bold"] as? String, "\(name) bold")
            #expect(parsed.runs.filter(\.italic).map(\.text).joined() == vector["italic"] as? String, "\(name) italic")
            #expect(parsed.runs.filter(\.strike).map(\.text).joined() == vector["strike"] as? String, "\(name) strike")
            #expect(parsed.runs.filter(\.code).map(\.text).joined() == vector["code"] as? String, "\(name) code")
            for link in parsed.runs.compactMap(\.link) { #expect(parsed.safetyText.contains(link)) }
        }
    }
    @Test func safeLinkSchemes() throws {
        for vector in try SharedVectors.array("message-formatting.json", key: "safeLinks") {
            let raw = try #require(vector["url"] as? String)
            #expect((BipeMessageText.safeLink(raw) != nil) == vector["allowed"] as? Bool)
        }
    }
    @Test func parserLimitsFallBackToBody() {
        #expect(BipeMessageText(body: "fallback", html: String(repeating: "x", count: 131073)).text == "fallback")
        let deep = String(repeating: "<div>", count: 70) + "deep" + String(repeating: "</div>", count: 70)
        #expect(BipeMessageText(body: "fallback", html: deep).text == "fallback")
    }
}
