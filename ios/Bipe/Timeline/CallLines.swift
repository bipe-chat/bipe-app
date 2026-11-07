// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec incoming-call notes: the timeline lines of a ring event.
enum CallLines {
    /// One line for a ring event and the call memberships after it (up to the next ring), and in a group one line for each member who joined.
    static func lines(id: String, event: EventTimelineItem, video: Bool, declinedBy: [String], active: Bool,
                      items: [TimelineItem], from index: Int, isDm: Bool,
                      name: (String) -> String) -> [(id: String, text: String)] {
        var memberEvents: [(userId: String, ts: Int64)] = []
        for item in items.dropFirst(index + 1) {
            guard let other = item.asEvent() else { continue }
            if case .rtcNotification = other.content { break }
            if case .state(_, .custom(let eventType)) = other.content, eventType == BipeCall.memberEventType {
                memberEvents.append((other.sender, Int64(other.timestamp)))
            }
        }
        let line = BipeCallRing.line(isDm: isDm, sender: event.sender, ringTs: Int64(event.timestamp), active: active,
                                     declinedBy: declinedBy, memberEvents: memberEvents)
        let label = video ? BipeStrings.callVideo : BipeStrings.callVoice
        let text: String
        switch line.outcome {
        case .ongoing: text = BipeStrings.callOngoing
        case .missed: text = BipeStrings.callMissed
        case .declined: text = BipeStrings.callDeclined
        case .ended: text = "\(label), \(BipeStrings.callDuration(String(line.minutes ?? 1)))"
        }
        var lines: [(id: String, text: String)] = [(id: id, text: text)]
        for userId in line.joined {
            lines.append((id: "\(id)-joined-\(userId)", text: BipeStrings.callJoined(name(userId))))
        }
        return lines
    }
}
