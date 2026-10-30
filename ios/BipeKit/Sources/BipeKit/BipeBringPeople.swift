// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The bring-people reminder rule (registries/onboarding.json bringPeople, bipe-chat/bipe#752). Port of
/// `bringPeopleReminder()` in bipe-shared tools/lib/registries.mjs; must match test-vectors/onboarding-schedule.json.
/// The Android counterpart is `BipeBringPeople` in libraries/bipe.
public nonisolated enum BipeBringPeople {
    public enum Kind: String, Sendable {
        case pending
        case expired
    }

    /// One filled slot as the rule sees it. `status` is the invite status (pending, used, expired, revoked).
    public struct RuleSlot: Sendable {
        public let status: String
        public let sentAt: Date

        public init(status: String, sentAt: Date) {
            self.status = status
            self.sentAt = sentAt
        }
    }

    private static let day: TimeInterval = 86_400

    /// The slot that the weekly reminder names, or nil. The oldest qualifying slot wins; on a tie, the first one.
    public static func reminder(declines: Int, lastAskAt: Date?, slots: [RuleSlot], now: Date) -> (slot: Int, kind: Kind)? {
        guard declines < BipeOnboarding.bringPeopleStopAfterDeclines, let lastAskAt,
              now.timeIntervalSince(lastAskAt) >= Double(BipeOnboarding.bringPeopleFollowUpEveryDays) * day else { return nil }
        var best: (slot: Int, kind: Kind)?
        for (index, slot) in slots.enumerated() {
            let kind: Kind?
            if slot.status == "expired" {
                kind = .expired
            } else if slot.status == "pending",
                      now.timeIntervalSince(slot.sentAt) >= Double(BipeOnboarding.bringPeoplePendingAfterDays) * day {
                kind = .pending
            } else {
                kind = nil
            }
            guard let kind else { continue }
            if best == nil || slot.sentAt < slots[best!.slot].sentAt { best = (index, kind) }
        }
        return best
    }
}

/// What bring-people remembers per account on this phone. Invite IDs only: the names stay on the server
/// (Invite.name) and the links stay in `BipeInviteStore`.
public nonisolated struct BipeBringPeopleMemory {
    private let defaults: UserDefaults
    private let prefix: String

    public init(userID: String, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        prefix = "bipe.bringPeople.\(userID)."
    }

    /// Set by signup-create: this account signed up on this phone, so the first sheet may show.
    public func markSignedUp() { defaults.set(true, forKey: prefix + "signedUp") }

    /// Trigger firstChatListAfterSignup: signed up here, the sheet never showed, and asks did not stop.
    public var sheetDue: Bool {
        defaults.bool(forKey: prefix + "signedUp") && !defaults.bool(forKey: prefix + "sheetShown") && !stopped
    }

    public var declines: Int { defaults.integer(forKey: prefix + "declines") }

    /// After `bringPeopleStopAfterDeclines` declines, no bring-people ask shows again on this account.
    public var stopped: Bool { declines >= BipeOnboarding.bringPeopleStopAfterDeclines }

    public var lastAskAt: Date? { defaults.object(forKey: prefix + "lastAskAt") as? Date }

    /// The invite ID of each filled slot, in the order they were filled.
    public var inviteIDs: [String] { defaults.stringArray(forKey: prefix + "invites") ?? [] }

    /// The sheet showed (first time or from the first-list card), or the person answered a reminder.
    public func asked(now: Date = Date(), sheet: Bool = false) {
        defaults.set(now, forKey: prefix + "lastAskAt")
        if sheet { defaults.set(true, forKey: prefix + "sheetShown") }
    }

    /// Agora não on the sheet or on the reminder.
    public func decline(now: Date = Date()) {
        defaults.set(declines + 1, forKey: prefix + "declines")
        asked(now: now)
    }

    /// Puts `inviteID` in slot `index`; an index past the end adds a slot.
    public func setInvite(_ inviteID: String, at index: Int) {
        var ids = inviteIDs
        if index < ids.count { ids[index] = inviteID } else { ids.append(inviteID) }
        defaults.set(ids, forKey: prefix + "invites")
    }

    /// Keeps only the slots whose invite the server still lists as pending, used or expired.
    public func keep(_ inviteIDs: Set<String>) {
        defaults.set(self.inviteIDs.filter(inviteIDs.contains), forKey: prefix + "invites")
    }
}
