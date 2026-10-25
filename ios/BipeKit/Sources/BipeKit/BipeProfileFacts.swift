// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Contract: `ProfileFacts` (ADR 0049). The account creation time and the dates of name and photo changes in the window.
/// Dates stay strings: a date that does not parse is ignored, as in bipe-shared `profileFactLines`.
public struct BipeProfileFacts: Codable, Sendable, Hashable {
    public let userId: String
    public let createdAt: String?
    public let nameChanges: [String]
    public let photoChanges: [String]

    /// The most Matrix IDs per `POST /v1/profile-facts` call.
    public static let batchSize = 100

    public init(userId: String, createdAt: String?, nameChanges: [String], photoChanges: [String]) {
        self.userId = userId
        self.createdAt = createdAt
        self.nameChanges = nameChanges
        self.photoChanges = photoChanges
    }

    private enum CodingKeys: String, CodingKey {
        case userId, createdAt, nameChanges, photoChanges
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decode(String.self, forKey: .userId)
        createdAt = try container.decodeIfPresent(String.self, forKey: .createdAt)
        nameChanges = try container.decodeIfPresent([String].self, forKey: .nameChanges) ?? []
        photoChanges = try container.decodeIfPresent([String].self, forKey: .photoChanges) ?? []
    }
}

/// The facts lines of chat-request and person-profile, and the amber notice of chat-timeline (ADR 0049).
/// Port of bipe-shared `profileFactLines` (tools/lib/registries.mjs); test-vectors/profile-facts.json must pass.
public nonisolated struct BipeProfileFactLines: Sendable, Equatable {
    public enum Fact: String, Sendable {
        case created
        case photo
        case name
    }

    public struct Line: Sendable, Equatable {
        public let fact: Fact
        /// The number of changes in the window. `nil` for `created`.
        public let count: Int?
        /// The creation time, or the latest change.
        public let at: Date

        public init(fact: Fact, count: Int?, at: Date) {
            self.fact = fact
            self.count = count
            self.at = at
        }
    }

    /// In order: created, photo, name. Empty when nothing applies.
    public let lines: [Line]
    /// A name change and a photo change at most noticePairHours apart, the later one younger than noticeDays.
    public let notice: Bool

    private static let dayMs: Int64 = 86_400_000
    private static let hourMs: Int64 = 3_600_000

    public init(facts: BipeProfileFacts, now: Date = Date()) {
        let nowMs = Self.milliseconds(now)
        let windowMs = Int64(BipeRegistries.profileFactsWindowDays) * Self.dayMs
        func inWindow(_ list: [String]) -> [Int64] {
            list.compactMap(Self.parse).map(Self.milliseconds)
                .filter { $0 <= nowMs && nowMs - $0 < windowMs }
                .sorted()
        }
        let names = inWindow(facts.nameChanges)
        let photos = inWindow(facts.photoChanges)
        var lines: [Line] = []
        if let created = facts.createdAt.flatMap(Self.parse).map(Self.milliseconds),
           created <= nowMs, nowMs - created < Int64(BipeRegistries.profileFactsNewAccountDays) * Self.dayMs {
            lines.append(Line(fact: .created, count: nil, at: Self.date(created)))
        }
        if let last = photos.last {
            lines.append(Line(fact: .photo, count: photos.count, at: Self.date(last)))
        }
        if let last = names.last {
            lines.append(Line(fact: .name, count: names.count, at: Self.date(last)))
        }
        let pairMs = Int64(BipeRegistries.profileFactsNoticePairHours) * Self.hourMs
        let noticeMs = Int64(BipeRegistries.profileFactsNoticeDays) * Self.dayMs
        var notice = false
        for name in names {
            for photo in photos where abs(name - photo) <= pairMs && nowMs - max(name, photo) < noticeMs {
                notice = true
            }
        }
        self.lines = lines
        self.notice = notice
    }

    /// The lines as text (spec chat-request and person-profile profileFacts).
    public func texts(now: Date = Date()) -> [String] {
        lines.map { line in
            let age = Self.age(since: line.at, now: now)
            let count = line.count ?? 1
            let window = String(BipeRegistries.profileFactsWindowDays)
            switch line.fact {
            case .created:
                return BipeStrings.profileFactCreated(age)
            case .photo:
                return count >= 2 ? BipeStrings.profileFactPhotoTimes(String(count), window) : BipeStrings.profileFactPhotoOnce(age)
            case .name:
                return count >= 2 ? BipeStrings.profileFactNameTimes(String(count), window) : BipeStrings.profileFactNameOnce(age)
            }
        }
    }

    /// The time since `date` in words, one unit, rounded down: "3 dias", "5 horas", "1 minuto".
    public static func age(since date: Date, now: Date = Date()) -> String {
        let seconds = max(60, now.timeIntervalSince(date))
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .full
        formatter.maximumUnitCount = 1
        let interval: TimeInterval
        if seconds >= 86_400 {
            formatter.allowedUnits = [.day]
            interval = (seconds / 86_400).rounded(.down) * 86_400
        } else if seconds >= 3_600 {
            formatter.allowedUnits = [.hour]
            interval = (seconds / 3_600).rounded(.down) * 3_600
        } else {
            formatter.allowedUnits = [.minute]
            interval = (seconds / 60).rounded(.down) * 60
        }
        return formatter.string(from: interval) ?? ""
    }

    /// An ISO 8601 date, with or without fractional seconds. Anything else is `nil`.
    static func parse(_ string: String) -> Date? {
        if let date = try? Date(string, strategy: .iso8601) {
            return date
        }
        return try? Date(string, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
    }

    static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func date(_ milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}
