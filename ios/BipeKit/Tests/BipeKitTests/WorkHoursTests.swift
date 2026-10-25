// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Work side opening hours (bipe-chat/bipe#511). The same cases as bipe-shared test-vectors/work-hours.json.
struct WorkHoursTests {
    private func hours(_ zone: String, _ days: [BipeWorkHours.Day]) -> BipeWorkHours {
        BipeWorkHours(enabled: true, timeZone: zone, days: days)
    }
    
    private func at(_ iso: String) -> Date {
        (try? Date(iso, strategy: .iso8601)) ?? .distantPast
    }
    
    @Test
    func vectors() {
        let monday = [BipeWorkHours.Day(day: 1, ranges: [.init(start: "08:00", end: "18:00")])]
        let sundayNight = [BipeWorkHours.Day(day: 7, ranges: [.init(start: "20:00", end: "24:00")])]
        let lunch = [BipeWorkHours.Day(day: 3, ranges: [.init(start: "08:00", end: "12:00"), .init(start: "13:00", end: "18:00")])]
        let cases: [(BipeWorkHours, String, Bool)] = [
            (hours("America/Sao_Paulo", monday), "2026-10-26T13:00:00Z", true),
            (hours("America/Sao_Paulo", monday), "2026-10-26T11:00:00Z", true),
            (hours("America/Sao_Paulo", monday), "2026-10-26T21:00:00Z", false),
            (hours("America/Sao_Paulo", monday), "2026-10-26T20:59:00Z", true),
            (hours("America/Sao_Paulo", monday), "2026-10-26T10:59:00Z", false),
            (hours("America/Sao_Paulo", monday), "2026-10-25T14:00:00Z", false),
            (hours("America/Sao_Paulo", sundayNight), "2026-10-26T01:30:00Z", true),
            (hours("America/Sao_Paulo", sundayNight), "2026-10-26T02:59:00Z", true),
            (hours("America/Sao_Paulo", lunch), "2026-10-28T15:30:00Z", false),
            (hours("America/Sao_Paulo", lunch), "2026-10-28T16:00:00Z", true),
            (hours("America/Manaus", monday), "2026-10-26T11:30:00Z", false),
            (hours("America/Manaus", monday), "2026-10-26T12:00:00Z", true),
            (hours("America/Sao_Paulo", []), "2026-10-26T12:00:00Z", false),
        ]
        for (value, iso, open) in cases {
            #expect(value.isOpen(at: at(iso)) == open, "\(iso)")
        }
    }
    
    @Test
    func quietOnlyWhenEnabled() {
        var value = hours("America/Sao_Paulo", [])
        #expect(value.isQuiet(at: .now))
        value.enabled = false
        #expect(!value.isQuiet(at: .now))
    }
}
