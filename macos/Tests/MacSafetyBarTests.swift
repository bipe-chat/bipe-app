// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

final class MacSafetyBarTests: XCTestCase {
    func testRetryOfAMessageHeldForAnIdentityChangeReadsSendAnyway() {
        var message = MacMessage(id: "held", sender: "Eu", senderID: "@me:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "Oi", richText: BipeMessageText(body: "Oi"), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: true, showsSender: false, pending: false, failed: true,
            warning: nil, eventID: nil, canReply: false, canReact: false, reactions: [])
        XCTAssertEqual(message.title(of: .retry), BipeMessageMenuItem.retry.title)
        message.sendFailure = .identity(users: ["@person:example.invalid"])
        XCTAssertEqual(message.title(of: .retry), BipeStrings.identityChangeSendAnyway)
        XCTAssertEqual(message.title(of: .delete), BipeMessageMenuItem.delete.title)
    }

    @MainActor
    func testBlockedUsersStartEmptyAndUnblockNeedsAnAccount() async {
        let blocked = MacBlockedUsers()
        XCTAssertFalse(blocked.isBlocked("@person:example.invalid"))
        do {
            try await blocked.unblock("@person:example.invalid")
            XCTFail("unblock without an account must fail")
        } catch {}
    }
}
