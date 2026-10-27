// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// The one-time m.direct cleanup of the news room (bipe-chat/bipe#809). Same cases as BipeNewsDirectCleanupTest.kt.
struct NewsDirectCleanupTests {
    private let news = "!news:example.test"

    private func object(_ json: String?) -> NSDictionary? {
        guard let json else { return nil }
        return (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? NSDictionary
    }

    @Test
    func removesTheNewsRoomAndTheEmptyUser() {
        let json = #"{"@bipe:example.test":["!news:example.test"],"@ana:example.test":["!dm:example.test"]}"#
        let out = BipeNewsDirectCleanup.json(removing: news, from: json)
        #expect(object(out) == ["@ana:example.test": ["!dm:example.test"]] as NSDictionary)
    }

    @Test
    func keepsOtherRoomsOfTheSameUser() {
        let json = #"{"@bipe:example.test":["!old:example.test","!news:example.test"]}"#
        let out = BipeNewsDirectCleanup.json(removing: news, from: json)
        #expect(object(out) == ["@bipe:example.test": ["!old:example.test"]] as NSDictionary)
    }

    @Test
    func nothingToWriteWhenTheRoomIsNotThere() {
        #expect(BipeNewsDirectCleanup.json(removing: news, from: #"{"@ana:example.test":["!dm:example.test"]}"#) == nil)
        #expect(BipeNewsDirectCleanup.json(removing: news, from: "{}") == nil)
        #expect(BipeNewsDirectCleanup.json(removing: news, from: nil) == nil)
        #expect(BipeNewsDirectCleanup.json(removing: news, from: "not json") == nil)
    }
}
