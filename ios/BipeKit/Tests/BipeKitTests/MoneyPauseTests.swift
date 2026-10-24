// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Spec scam-warning, money pause.
struct MoneyPauseTests {
    private let start = Date(timeIntervalSince1970: 1_000)

    @Test
    func pixCodeStartsAFiveSecondPause() {
        var pause = BipeMoneyPause()
        #expect(pause.start(id: "$e", kind: .pixCode, now: start))
        #expect(pause.secondsLeft(id: "$e", now: start) == 5)
        #expect(pause.secondsLeft(id: "$e", now: start.addingTimeInterval(4.2)) == 1)
        #expect(pause.secondsLeft(id: "$e", now: start.addingTimeInterval(5)) == 0)
    }

    @Test
    func runsOncePerMessage() {
        var pause = BipeMoneyPause()
        pause.start(id: "$e", kind: .bankAlert, now: start)
        #expect(!pause.start(id: "$e", kind: .bankAlert, now: start.addingTimeInterval(10)))
        #expect(pause.secondsLeft(id: "$e", now: start.addingTimeInterval(10)) == 0)
    }

    @Test
    func kindsWithoutMoneyGetNoPause() {
        var pause = BipeMoneyPause()
        for kind in [ScamWarningKind.receipt, .newNumber, .urgency, .pinRequest] {
            #expect(!pause.start(id: kind.rawValue, kind: kind, now: start))
            #expect(pause.secondsLeft(id: kind.rawValue, now: start) == 0)
        }
        #expect(BipeMoneyPause.applies(to: .lookalikeLink))
    }
}
