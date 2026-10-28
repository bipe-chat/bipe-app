// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct AppLockTriesTests {
    @Test
    func firstTriesAreFree() {
        for failures in 0..<BipeAppLockTries.freeTries {
            #expect(BipeAppLockTries.wait(failures: failures) == nil)
        }
    }

    @Test
    func waitGrowsThenRepeats() {
        let free = BipeAppLockTries.freeTries
        #expect(BipeAppLockTries.wait(failures: free) == 30)
        #expect(BipeAppLockTries.wait(failures: free + 1) == 60)
        #expect(BipeAppLockTries.wait(failures: free + 4) == 3600)
        #expect(BipeAppLockTries.wait(failures: free + 40) == 3600)
    }

    /// Ten wrong PINs in a row never come quickly (bipe-chat/bipe#777): after the free tries, each one waits.
    @Test
    func tenWrongPinsTakeOverAnHour() {
        let total = (BipeAppLockTries.freeTries..<10).compactMap { BipeAppLockTries.wait(failures: $0) }.reduce(0, +)
        #expect(total >= 3600)
    }

    @Test
    func waitEndsAfterItsTime() {
        let last = Date(timeIntervalSince1970: 1_000)
        let free = BipeAppLockTries.freeTries
        #expect(BipeAppLockTries.waitEnd(failures: free, lastFailure: last, now: last) == last.addingTimeInterval(30))
        #expect(BipeAppLockTries.waitEnd(failures: free, lastFailure: last, now: last.addingTimeInterval(29)) != nil)
        #expect(BipeAppLockTries.waitEnd(failures: free, lastFailure: last, now: last.addingTimeInterval(30)) == nil)
        #expect(BipeAppLockTries.waitEnd(failures: free - 1, lastFailure: last, now: last) == nil)
    }
}
