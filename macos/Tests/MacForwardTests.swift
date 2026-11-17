// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

final class MacForwardTests: XCTestCase {
    private func message(_ id: String, forwardJson: String?, isOwn: Bool = false, canDelete: Bool = false) -> MacMessage {
        var message = MacMessage(id: id, sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "Oi", richText: BipeMessageText(body: "Oi"), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: isOwn, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$" + id, canReply: true, canReact: true, reactions: [])
        message.forwardJson = forwardJson
        message.canDelete = canDelete
        return message
    }

    func testForwardChatLimitFollowsTheMark() {
        let fresh = message("a", forwardJson: #"{"msgtype":"m.text","body":"Oi","chat.bipe.forward":1}"#)
        XCTAssertEqual(MacForwarding(messages: [fresh]).chatLimit, BipeRegistries.forwardChats)
        let many = message("b", forwardJson: #"{"msgtype":"m.text","body":"Oi","chat.bipe.forward":50}"#)
        XCTAssertEqual(MacForwarding(messages: [fresh, many]).chatLimit, BipeRegistries.forwardChatsManyTimes)
        XCTAssertEqual(MacForwarding(messages: [many]).limitText, BipeStrings.forwardLimitMany)
    }

    @MainActor
    func testSelectionPicksForwardableOrOwnDeletableMessagesUpToTheLimit() {
        let model = MacTimeline(drafts: MacDrafts())
        let other = message("x", forwardJson: nil)
        XCTAssertFalse(model.canSelect(other))
        let own = message("y", forwardJson: nil, isOwn: true, canDelete: true)
        XCTAssertTrue(model.canSelect(own))
        model.startSelection(own)
        XCTAssertEqual(model.selection, ["y"])
        model.toggleSelection(own)
        XCTAssertNil(model.selection)
    }
}
