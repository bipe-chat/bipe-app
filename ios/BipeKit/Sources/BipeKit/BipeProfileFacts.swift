// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Contract: `ProfileFacts` (ADR 0049). The account creation time and the dates of name and photo changes in the window.
/// Dates stay strings: a date that does not parse is ignored, as in bipe-shared `profileFactLines`.
public struct BipeProfileFacts: Codable, Sendable, Hashable {
    public let userId: String
    public let createdAt: String?
    public let nameChanges: [String]
    public let photoChanges: [String]
    /// When this account's number went to another account, and when it got one from another account (bipe-chat/bipe#161).
    public let numberMovedOut: [String]
    public let numberMovedIn: [String]

    /// The most Matrix IDs per `POST /v1/profile-facts` call.
    public static let batchSize = 100

    public init(userId: String, createdAt: String?, nameChanges: [String], photoChanges: [String], numberMovedOut: [String] = [], numberMovedIn: [String] = []) {
        self.userId = userId
        self.createdAt = createdAt
        self.nameChanges = nameChanges
        self.photoChanges = photoChanges
        self.numberMovedOut = numberMovedOut
        self.numberMovedIn = numberMovedIn
    }

    private enum CodingKeys: String, CodingKey {
        case userId, createdAt, nameChanges, photoChanges, numberMovedOut, numberMovedIn
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        userId = try container.decode(String.self, forKey: .userId)
        createdAt = try container.decodeIfPresent(String.self, forKey: .createdAt)
        nameChanges = try container.decodeIfPresent([String].self, forKey: .nameChanges) ?? []
        photoChanges = try container.decodeIfPresent([String].self, forKey: .photoChanges) ?? []
        numberMovedOut = try container.decodeIfPresent([String].self, forKey: .numberMovedOut) ?? []
        numberMovedIn = try container.decodeIfPresent([String].self, forKey: .numberMovedIn) ?? []
    }
}

/// The facts lines of chat-request and person-profile, and the amber notice of chat-timeline (ADR 0049).
/// bipe-shared `profileFactLines`, run in bipe-core; test-vectors/profile-facts.json must pass.
public nonisolated struct BipeProfileFactLines: Sendable, Equatable {
    public enum Fact: String, Sendable {
        case created
        case photo
        case name
        case numberOut
        case numberIn
    }

    /// Which way the newest number move in the window went (bipe-chat/bipe#161).
    public enum NumberNotice: String, Sendable {
        case out
        case `in`
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

    /// In order: created, photo, name, numberOut, numberIn. Empty when nothing applies.
    public let lines: [Line]
    /// A name change and a photo change at most noticePairHours apart, the later one younger than noticeDays.
    public let notice: Bool
    /// The newest number move in the window, `nil` without one.
    public let numberNotice: NumberNotice?

    /// `alwaysCreated`: person-profile keeps the account age at any age (ADR 0275 point 13). The rule runs in bipe-core
    /// (`profileFactLines`).
    public init(facts: BipeProfileFacts, now: Date = Date(), alwaysCreated: Bool = false) {
        let input = ProfileFacts(createdAt: facts.createdAt, nameChanges: facts.nameChanges, photoChanges: facts.photoChanges,
                                 numberMovedOut: facts.numberMovedOut, numberMovedIn: facts.numberMovedIn)
        let result = profileFactLines(facts: input, nowMs: Self.milliseconds(now), alwaysCreated: alwaysCreated)
        lines = result.lines.map { line in
            let fact: Fact = switch line.fact {
            case .created: .created
            case .photo: .photo
            case .name: .name
            case .numberOut: .numberOut
            case .numberIn: .numberIn
            }
            return Line(fact: fact, count: line.count.map(Int.init), at: Self.date(line.atMs))
        }
        notice = result.notice
        numberNotice = result.numberNotice.map { $0 == .in ? .in : .out }
    }
    
    /// The text of the amber bar of chat-timeline, `nil` without a notice. A number move wins over a name and photo change.
    public func noticeText(name: String) -> String? {
        switch numberNotice {
        case .out: BipeStrings.profileNoticeNumberOut(name)
        case .in: BipeStrings.profileNoticeNumberIn(name)
        case nil: notice ? BipeStrings.profileNoticeNamePhoto(name) : nil
        }
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
            case .numberOut:
                return BipeStrings.profileFactNumberOut(age)
            case .numberIn:
                return BipeStrings.profileFactNumberIn(age)
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
