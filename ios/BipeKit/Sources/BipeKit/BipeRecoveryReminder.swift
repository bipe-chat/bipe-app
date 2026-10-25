// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Timing of the recovery-reminder card (bipe-chat/bipe#531, spec recovery-reminder; registries/accounts.json
/// recoveryReminder). The card never stops for good while the account has no Team-ID-independent way back.
public enum BipeRecoveryReminder {
    /// True when the card may show: the account came to this phone `firstAfterDays` ago and the snooze has ended.
    public static func isDue(now: Date, since: Date, snoozedUntil: Date?) -> Bool {
        let first = since.addingTimeInterval(TimeInterval(BipeRegistries.recoveryReminderFirstAfterDays) * 86_400)
        return now >= first && now >= (snoozedUntil ?? .distantPast)
    }

    /// The end of the snooze after the `count`-th "Agora não" (0 for the first). Past the list, the last value repeats.
    public static func snoozedUntil(now: Date, count: Int) -> Date {
        let days = BipeRegistries.recoveryReminderSnoozeDays
        let index = min(max(count, 0), days.count - 1)
        return now.addingTimeInterval(TimeInterval(days[index]) * 86_400)
    }
}
