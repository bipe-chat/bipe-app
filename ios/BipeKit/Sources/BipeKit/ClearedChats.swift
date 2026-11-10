// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Cleared DMs ("Apagar conversa", bipe#464): the global account data event `chat.bipe.cleared_chats`,
/// content `{ "rooms": { "<room_id>": { "ts": <origin_server_ts in ms> } } }`.
/// Port of `clearedChat*` in bipe-shared `tools/lib/registries.mjs`; `test-vectors/cleared-chats.json` holds the cases.
public struct ClearedChats: Equatable, Sendable {
    public static let eventType = BipeRegistries.clearedChatsEventType
    public static let empty = ClearedChats(markers: [:])
    
    /// The valid markers by room ID. A malformed event or entry counts as not cleared.
    public let markers: [String: UInt64]
    
    public init(markers: [String: UInt64]) {
        self.markers = markers
    }
    
    /// Reads a content object from `JSONSerialization`. Anything other than an object with a `rooms` object is empty.
    public init(content: Any?) {
        self.init(json: content.flatMap(CoreJSON.text))
    }
    
    /// Reads the account data as the SDK returns it: a JSON string, `nil` when the event does not exist
    /// (bipe-core `clearedChatMarkers`).
    public init(json: String?) {
        self.init(markers: clearedChatMarkers(contentJson: json).mapValues { UInt64($0) })
    }
    
    public func marker(for roomID: String) -> UInt64? {
        markers[roomID]
    }
    
    /// True when the chat list hides the room: it has a marker and no event after it. `latestTs` is `nil` when the room has no latest event.
    public static func isHidden(marker: UInt64?, latestTs: UInt64?) -> Bool {
        clearedChatHidden(marker: marker.map { Int64(clamping: $0) }, latestTs: latestTs.map { Int64(clamping: $0) })
    }
    
    /// True when the timeline and search keep an event with this `origin_server_ts`.
    public static func keepsEvent(marker: UInt64?, eventTs: UInt64) -> Bool {
        clearedChatKeepsEvent(marker: marker.map { Int64(clamping: $0) }, eventTs: Int64(clamping: eventTs))
    }
    
    /// The content to write when the user clears `roomID` at `ts` (bipe-core `clearedChatsWithClear`). Keeps every other
    /// key and entry as it is. The marker never moves back.
    public static func content(clearing content: Any?, roomID: String, at ts: UInt64) -> [String: Any] {
        CoreJSON.object(json(clearing: content.flatMap(CoreJSON.text), roomID: roomID, at: ts)) ?? [:]
    }
    
    /// `content(clearing:roomID:at:)` on the JSON string the SDK returns.
    public static func json(clearing json: String?, roomID: String, at ts: UInt64) -> String? {
        clearedChatsWithClear(contentJson: json, roomId: roomID, ts: Int64(clamping: ts))
    }
}
