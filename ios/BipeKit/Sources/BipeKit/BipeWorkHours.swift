// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Contract: `WorkHours`. The opening hours of a work account (ADR 0138 rule 4, bipe-chat/bipe#511; spec work-hours).
/// Outside them the phone shows that account's pushes with no sound and no banner.
public struct BipeWorkHours: Codable, Sendable, Equatable {
    /// Contract: `WorkRange`. Local HH:MM, half-open; end 24:00 is midnight.
    public struct Range: Codable, Sendable, Equatable {
        public var start: String
        public var end: String
        
        public init(start: String, end: String) {
            self.start = start
            self.end = end
        }
    }
    
    /// Contract: `WorkDay`. ISO weekday, 1 Monday to 7 Sunday.
    public struct Day: Codable, Sendable, Equatable {
        public var day: Int
        public var ranges: [Range]
        
        public init(day: Int, ranges: [Range]) {
            self.day = day
            self.ranges = ranges
        }
    }
    
    public var enabled: Bool
    public var timeZone: String
    public var days: [Day]
    
    public init(enabled: Bool, timeZone: String, days: [Day]) {
        self.enabled = enabled
        self.timeZone = timeZone
        self.days = days
    }
    
    /// The registry defaults (registries/work-hours.json), off, in the phone's time zone.
    public static var defaults: BipeWorkHours {
        BipeWorkHours(enabled: false, timeZone: TimeZone.current.identifier,
                      days: BipeRegistries.workHoursDefaultDays.map { Day(day: $0.day, ranges: $0.ranges.map { Range(start: $0.start, end: $0.end) }) })
    }
    
    /// Same rule as isWithinWorkHours() in bipe-shared tools/lib/registries.mjs (test-vectors/work-hours.json).
    public func isOpen(at date: Date) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: timeZone) ?? TimeZone(identifier: BipeRegistries.workHoursDefaultTimeZone) ?? .current
        let parts = calendar.dateComponents([.weekday, .hour, .minute], from: date)
        // Calendar weekday: 1 Sunday ... 7 Saturday. ISO: 1 Monday ... 7 Sunday.
        let iso = ((parts.weekday ?? 1) + 5) % 7 + 1
        let minute = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        guard let day = days.first(where: { $0.day == iso }) else { return false }
        return day.ranges.contains { range in
            guard let start = Self.minutes(range.start), let end = Self.minutes(range.end) else { return false }
            return minute >= start && minute < end
        }
    }
    
    /// True when the hours are on and `date` is outside them: the account's pushes arrive quietly.
    public func isQuiet(at date: Date) -> Bool {
        enabled && !isOpen(at: date)
    }
    
    public static func minutes(_ hhmm: String) -> Int? {
        let parts = hhmm.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...24).contains(hour), (0..<60).contains(minute), hour < 24 || minute == 0 else { return nil }
        return hour * 60 + minute
    }
}

public extension BipeAPIClient {
    /// `GET /v1/me/work-hours`. Company accounts only (404 otherwise).
    func workHours(accessToken: String) async throws(BipeAPIError) -> BipeWorkHours {
        try await send(method: "GET", path: ["v1", "me", "work-hours"], body: Empty?.none, accessToken: accessToken)
    }
    
    /// `PUT /v1/me/work-hours`.
    func setWorkHours(_ hours: BipeWorkHours, accessToken: String) async throws(BipeAPIError) -> BipeWorkHours {
        try await send(method: "PUT", path: ["v1", "me", "work-hours"], body: hours, accessToken: accessToken)
    }
}
