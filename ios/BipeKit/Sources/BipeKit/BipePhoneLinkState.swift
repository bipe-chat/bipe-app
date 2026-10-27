// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Spec phone-link-status (bipe-chat/bipe#470): the state of the person's linked number and its yearly confirmation.
/// A linked number stays findable until `confirm_by`; the app warns `phoneLinkConfirmWarnDays` before it.
public enum BipePhoneLinkState: Equatable, Sendable {
    case notLinked
    case linked(until: Date)
    case confirmSoon(by: Date)
    case expired(since: Date)
    
    public init(phone: BipeMyPhone, now: Date = .now) {
        guard phone.linked, let confirmBy = phone.confirmBy else {
            self = phone.linked ? .linked(until: .distantFuture) : .notLinked
            return
        }
        if confirmBy <= now {
            self = .expired(since: confirmBy)
        } else if confirmBy.timeIntervalSince(now) <= TimeInterval(BipeRegistries.phoneLinkConfirmWarnDays) * 86_400 {
            self = .confirmSoon(by: confirmBy)
        } else {
            self = .linked(until: confirmBy)
        }
    }
    
    /// confirm_by, for the once-per-date notice.
    public var confirmBy: Date? {
        switch self {
        case .notLinked: nil
        case .linked(let date), .confirmSoon(let date), .expired(let date): date == .distantFuture ? nil : date
        }
    }
    
    /// The caution colour and the warning mark on the row, and the in-app notice.
    public var needsAttention: Bool {
        switch self {
        case .confirmSoon, .expired: true
        case .notLinked, .linked: false
        }
    }
    
    /// The value of the privacy row number.
    public var summary: String {
        switch self {
        case .notLinked: BipeStrings.privacyNumberNotLinked
        case .linked(let until): until == .distantFuture ? BipeStrings.privacyNumberLinked : BipeStrings.phoneLinkedUntil(Self.format(until))
        case .confirmSoon(let by): BipeStrings.phoneConfirmSoon(Self.format(by))
        case .expired: BipeStrings.phoneConfirmExpired
        }
    }
    
    /// The value of the privacy row number with the own linked number (ADR 0191, bipe-chat/bipe#787): the number formatted
    /// for reading next to the state. Without a number (not linked, or a link made before the server stored it): the state only.
    public func summary(number: String?) -> String {
        guard self != .notLinked, let number, !number.isEmpty else { return summary }
        return BipeStrings.privacyNumberValue(BipePhoneNumber.display(number), summary)
    }

    private static func format(_ date: Date) -> String {
        date.formatted(date: .long, time: .omitted)
    }
}
