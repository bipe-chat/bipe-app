// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import XCTest
@testable import BipeMac

final class MacJumpToDateTests: XCTestCase {
    private func day(_ offset: Int) -> Date {
        Calendar.current.date(byAdding: .day, value: offset, to: Calendar.current.startOfDay(for: Date(timeIntervalSince1970: 1_800_000_000)))!
    }

    func testFirstRowOfADayAndWhetherTheRowsReachIt() {
        let rows: [MacTimelineRow] = [
            .day(id: "d1", date: day(0)),
            .line(id: "l1", text: "x", kind: .info),
            .day(id: "d2", date: day(2)),
        ]
        XCTAssertEqual(MacTimeline.firstRow(onOrAfter: day(1), rows: rows), "d2")
        XCTAssertEqual(MacTimeline.firstRow(onOrAfter: day(0), rows: rows), "d1")
        XCTAssertNil(MacTimeline.firstRow(onOrAfter: day(3), rows: rows))
        XCTAssertTrue(MacTimeline.reaches(day(1), rows: rows, reachedStart: false))
        XCTAssertFalse(MacTimeline.reaches(day(0), rows: rows, reachedStart: false))
        XCTAssertTrue(MacTimeline.reaches(day(-5), rows: rows, reachedStart: true))
    }
}
