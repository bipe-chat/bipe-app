// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct ImageDescriptionTests {
    private let field = BipeRegistries.imageDescriptionField

    @Test
    func sendsTrimmedTextAndNothingWhenBlank() throws {
        #expect(BipeImageDescription.extraContentJson("  ") == nil)
        #expect(BipeImageDescription.extraContentJson(nil) == nil)
        let json = try #require(BipeImageDescription.extraContentJson("  Um cachorro na praia \n"))
        let object = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: String]
        #expect(object == [field: "Um cachorro na praia"])
    }

    @Test
    func cutsAtTheLimit() {
        let long = String(repeating: "a", count: BipeRegistries.imageDescriptionMaxLength + 50)
        #expect(BipeImageDescription.clean(long)?.count == BipeRegistries.imageDescriptionMaxLength)
    }

    @Test
    func readsOnlyAStringOnAnImage() {
        let image = #"{"type":"m.room.message","content":{"msgtype":"m.image","body":"a.jpg","FIELD":" Bolo de fubá "}}"#
            .replacingOccurrences(of: "FIELD", with: field)
        #expect(BipeImageDescription.read(eventJson: image) == "Bolo de fubá")
        let video = image.replacingOccurrences(of: "m.image", with: "m.video")
        #expect(BipeImageDescription.read(eventJson: video) == nil)
        let number = #"{"content":{"msgtype":"m.image","FIELD":42}}"#.replacingOccurrences(of: "FIELD", with: field)
        #expect(BipeImageDescription.read(eventJson: number) == nil)
        #expect(BipeImageDescription.read(eventJson: "not json") == nil)
        #expect(BipeImageDescription.read(eventJson: nil) == nil)
    }
}
