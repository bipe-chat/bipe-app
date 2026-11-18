// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

final class MacTypingTests: XCTestCase {
    func testTypingLineFollowsTheSpecNameRules() {
        XCTAssertNil(MacPresence.text([]))
        XCTAssertEqual(MacPresence.text(["Ana"]), BipeStrings.appTypingOne("Ana"))
        XCTAssertEqual(MacPresence.text(["Ana", "Bia"]), BipeStrings.appTypingTwo("Ana", "Bia"))
        XCTAssertEqual(MacPresence.text(["Ana", "Bia", "Caio"]), BipeStrings.appTypingMany("Ana"))
    }

    func testPresenceIsOnUntilTurnedOff() throws {
        let suite = "chat.bipe.mac.tests.presence." + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertTrue(MacPresence.sharesTyping(defaults))
        defaults.set(false, forKey: MacPresence.key)
        XCTAssertFalse(MacPresence.sharesTyping(defaults))
    }

    func testPinTextUsesTheCaptionOrTheMediaLabelOnOneLine() {
        let text = MacMessagePayload(body: "linha um\nlinha dois")
        XCTAssertEqual(MacPinsModel.text(text, kind: .redacted), "linha um linha dois")
    }
}
