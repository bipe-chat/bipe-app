// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// bipe-shared test-vectors/notice-links.json (ADR 0036, bipe-chat/bipe#245).
struct NoticeLinksTests {
    @Test
    func sharedVectors() throws {
        let object = try SharedVectors.object("notice-links.json")
        let domains = try #require(object["domains"] as? [String])
        let vectors = try #require(object["vectors"] as? [[String: Any]])
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let url = try #require(vector["url"] as? String)
            let expected = try #require(vector["clickable"] as? Bool)
            #expect(BipeNoticeLinks.isClickable(url, domains: domains) == expected, "\(vector["name"] ?? url)")
        }
    }
}
