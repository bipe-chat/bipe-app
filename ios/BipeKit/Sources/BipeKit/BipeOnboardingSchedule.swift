// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The PIN reminder schedule (registries/onboarding.json pinReminder, bipe-chat/bipe#202). Port of `pinReminderNext()`
/// in bipe-shared tools/lib/registries.mjs; must match test-vectors/onboarding-schedule.json.
public nonisolated enum BipeOnboardingSchedule {
    public enum PinResult: String, Sendable {
        case right
        case wrong
        case later
    }

    /// The PIN reminder after a check: the next step and the days until the next reminder.
    public static func pinReminderNext(step: Int, result: PinResult) -> (step: Int, gapDays: Int) {
        if result == .later { return (step, 1) }
        let next = result == .right ? step + 1 : 0
        return (next, gapDays(after: next))
    }

    /// The gap before the first reminder is `gapDays(after: 0)`.
    public static func gapDays(after step: Int) -> Int {
        step < BipeOnboarding.pinReminderDays.count ? BipeOnboarding.pinReminderDays[step] : BipeOnboarding.pinReminderThenEveryDays
    }
}

/// What the PIN reminder remembers per account on this phone. Holds no secret: never the PIN.
public nonisolated struct BipePinReminderMemory {
    private let defaults: UserDefaults
    private let prefix: String
    private static let day: TimeInterval = 86_400

    public init(userID: String, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        prefix = "bipe.pinReminder.\(userID)."
    }

    /// When this phone first saw the account; set on the first read.
    public var firstSeen: Date {
        if let value = defaults.object(forKey: prefix + "firstSeen") as? Date { return value }
        let now = Date()
        defaults.set(now, forKey: prefix + "firstSeen")
        return now
    }

    public var step: Int { defaults.integer(forKey: prefix + "step") }

    /// When the next reminder is due. The first one comes `gapDays(after: 0)` after this phone first saw the account.
    public var nextAt: Date {
        if let value = defaults.object(forKey: prefix + "nextAt") as? Date { return value }
        return firstSeen.addingTimeInterval(TimeInterval(BipeOnboardingSchedule.gapDays(after: 0)) * Self.day)
    }

    public func isDue(now: Date = Date()) -> Bool { now >= nextAt }

    public func checked(_ result: BipeOnboardingSchedule.PinResult, now: Date = Date()) {
        let next = BipeOnboardingSchedule.pinReminderNext(step: step, result: result)
        defaults.set(next.step, forKey: prefix + "step")
        defaults.set(now.addingTimeInterval(TimeInterval(next.gapDays) * Self.day), forKey: prefix + "nextAt")
    }
}
