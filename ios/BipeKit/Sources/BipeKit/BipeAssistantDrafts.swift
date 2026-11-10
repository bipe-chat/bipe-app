// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Assistant drafts (bipe ADR 0210, bipe-chat/bipe#866; registries/drafts.json, bipe-shared docs/drafts-room.md).
/// A draft is an m.text message in the account's private drafts room. The field chat.bipe.draft names the target chat.
/// The rules match draftFromEvent in bipe-shared tools/lib/registries.mjs and test-vectors/drafts.json.
public enum BipeAssistantDrafts {
    /// One draft that the apps show.
    public struct Draft: Equatable, Sendable {
        public var roomId: String
        public var body: String
        public var replyTo: String?
        public var threadRoot: String?
        public var client: String

        public init(roomId: String, body: String, replyTo: String?, threadRoot: String?, client: String) {
            self.roomId = roomId
            self.body = body
            self.replyTo = replyTo
            self.threadRoot = threadRoot
            self.client = client
        }
    }

    /// The result of draftFromEvent: a draft, or the reason the apps do not show the event.
    public enum Result: Equatable, Sendable {
        case show(Draft)
        case hide(reason: String)

        public var draft: Draft? {
            if case .show(let draft) = self { return draft }
            return nil
        }
    }

    /// The drafts room ID from the content of the global account data chat.bipe.drafts_room. nil when absent or malformed.
    public static func roomId(accountDataJson json: String?) -> String? {
        guard let data = json?.data(using: .utf8),
              let content = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let id = content["room_id"] as? String, id.hasPrefix("!"), !id.contains(where: \.isWhitespace) else { return nil }
        return id
    }

    /// draftFromEvent for the raw JSON of a drafts room event. sender (EventTimelineItem.sender) replaces the JSON
    /// sender when given, and redacted marks a redacted timeline item.
    public static func fromEventJson(_ json: String?, sender: String? = nil, redacted: Bool = false,
                                     ownUserId: String, joinedRoomIds: Set<String>) -> Result {
        guard let data = json?.data(using: .utf8),
              var event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return .hide(reason: "type") }
        if let sender { event["sender"] = sender }
        if redacted || ((event["unsigned"] as? [String: Any])?["redacted_because"] != nil) { event["redacted"] = true }
        return fromEvent(event, ownUserId: ownUserId, joinedRoomIds: joinedRoomIds)
    }

    /// draftFromEvent (bipe-core): event is { sender, type, redacted?, content }, an event of the drafts room.
    public static func fromEvent(_ event: [String: Any], ownUserId: String, joinedRoomIds: Set<String>) -> Result {
        guard let json = CoreJSON.text(event) else { return .hide(reason: "type") }
        switch draftFromEvent(eventJson: json, ownUserId: ownUserId, joinedRoomIds: Array(joinedRoomIds)) {
        case .show(let draft):
            return .show(Draft(roomId: draft.roomId, body: draft.body, replyTo: draft.replyTo, threadRoot: draft.threadRoot, client: draft.client))
        case .hide(let reason):
            return .hide(reason: reason)
        }
    }
}
