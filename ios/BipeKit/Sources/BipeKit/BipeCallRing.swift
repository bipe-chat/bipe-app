// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// What one ring event means for this device (spec incoming-call).
public struct BipeCallRingRead: Equatable, Sendable {
    /// A DM ring for the own user, inside its lifetime.
    public var ring: Bool
    /// A group call notification for the own user, inside its lifetime.
    public var notify: Bool
    public var video: Bool
    public var expiresAt: Int64
}

/// The timeline call line (spec incoming-call notes, timeline-rows).
public struct BipeCallLine: Equatable, Sendable {
    public enum Outcome: String, Sendable {
        case ongoing
        case missed
        case declined
        case ended
    }

    public var outcome: Outcome
    /// Outcome ended: the length in whole minutes, at least 1.
    public var minutes: Int?
    /// Groups: the members who joined, other than the caller, in order.
    public var joined: [String]
}

/// MSC4075 ringing (bipe-chat/bipe#759, ADR 0186). Vectors: bipe-shared test-vectors/calls/ringing.json;
/// Android has the same rules in BipeCallRing.kt.
public enum BipeCallRing {
    public static let eventType = "org.matrix.msc4075.rtc.notification"
    public static let declineEventType = "org.matrix.msc4310.rtc.decline"
    public static let lifetimeMs: Int64 = 30_000
    /// The longest ring a device accepts, whatever the sender says.
    public static let maxLifetimeMs: Int64 = 120_000
    /// A sender clock further off than this from the server time is not used.
    public static let maxClockSkewMs: Int64 = 20_000

    public static func content(notificationType: String, senderTs: Int64, membershipEventId: String, userIds: [String],
                               video: Bool) -> [String: Any] {
        let mentions: [String: Any] = userIds.isEmpty ? ["room": true] : ["user_ids": userIds]
        return [
            "notification_type": notificationType,
            "sender_ts": senderTs,
            "lifetime": lifetimeMs,
            "m.mentions": mentions,
            "m.relates_to": ["rel_type": "m.reference", "event_id": membershipEventId],
            "m.call.intent": video ? "video" : "audio",
        ]
    }

    public static func read(content: [String: Any], sender: String, originServerTs: Int64, ownUserId: String,
                            now: Int64) -> BipeCallRingRead {
        let senderTs = (content["sender_ts"] as? NSNumber)?.int64Value ?? originServerTs
        let base = abs(senderTs - originServerTs) <= maxClockSkewMs ? senderTs : originServerTs
        let lifetime = min(max((content["lifetime"] as? NSNumber)?.int64Value ?? lifetimeMs, 0), maxLifetimeMs)
        let expiresAt = base + lifetime
        let video = content["m.call.intent"] as? String == "video"
        let mentions = content["m.mentions"] as? [String: Any]
        let addressed = (mentions?["user_ids"] as? [String])?.contains(ownUserId) == true || mentions?["room"] as? Bool == true
        let live = now < expiresAt && sender != ownUserId && addressed
        let type = content["notification_type"] as? String
        return BipeCallRingRead(ring: live && type == "ring", notify: live && type == "notification", video: video, expiresAt: expiresAt)
    }

    /// memberEvents: (user, time) of each org.matrix.msc3401.call.member state event of the room.
    public static func line(isDm: Bool, sender: String, ringTs: Int64, active: Bool, declinedBy: [String],
                            memberEvents: [(userId: String, ts: Int64)]) -> BipeCallLine {
        let after = memberEvents.filter { $0.ts >= ringTs }
        var joined: [String] = []
        for event in after where event.userId != sender && !joined.contains(event.userId) {
            joined.append(event.userId)
        }
        let groupJoined = isDm ? [] : joined
        if active { return BipeCallLine(outcome: .ongoing, minutes: nil, joined: groupJoined) }
        if joined.isEmpty {
            return BipeCallLine(outcome: isDm && !declinedBy.isEmpty ? .declined : .missed, minutes: nil, joined: [])
        }
        let last = after.map(\.ts).max() ?? ringTs
        let minutes = max(1, Int((last - ringTs + 30_000) / 60_000))
        return BipeCallLine(outcome: .ended, minutes: minutes, joined: groupJoined)
    }
}
