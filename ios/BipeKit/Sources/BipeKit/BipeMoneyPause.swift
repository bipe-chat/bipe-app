// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Spec scam-warning, money pause (security.md rule 9, trust-ui.md): copy and links of a message with a money warning
/// stay off for `BipeRegistries.scamMoneyPauseSeconds`. One pause per message for the life of this value (one open chat).
public nonisolated struct BipeMoneyPause: Sendable, Equatable {
    private var ends: [String: Date] = [:]

    public init() {}

    /// `true` for the kinds in `BipeRegistries.scamMoneyPauseKinds`.
    public static func applies(to kind: ScamWarningKind) -> Bool {
        BipeRegistries.scamMoneyPauseKinds.contains(kind.rawValue)
    }

    /// Starts the pause the first time the card of `id` shows. Returns `false` when it does not apply or already ran.
    @discardableResult
    public mutating func start(id: String, kind: ScamWarningKind, now: Date = .now) -> Bool {
        guard Self.applies(to: kind), ends[id] == nil else { return false }
        ends[id] = now.addingTimeInterval(TimeInterval(BipeRegistries.scamMoneyPauseSeconds))
        return true
    }

    /// Whole seconds left, rounded up; 0 when the pause never started or has ended.
    public func secondsLeft(id: String, now: Date = .now) -> Int {
        guard let end = ends[id] else { return 0 }
        let left = end.timeIntervalSince(now)
        return left <= 0 ? 0 : Int(left.rounded(.up))
    }
}
