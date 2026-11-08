// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Notes room of a work account (bipe ADR 0284 points 18 and 19, bipe-chat/bipe#1293; bipe-shared
/// registries/work-notes.json). One private, encrypted room with only the account's own devices, like the drafts room.
/// A note is an m.text message whose field chat.bipe.work_note names the customer chat and the seat that wrote it. A
/// reply-by is an m.notice message whose field chat.bipe.reply_by names the seat that sent a reply to a customer. Both
/// are m.room.message because matrix-sdk-ui drops custom event types from the timeline. The rules match workNoteFromEvent and replyByFromEvent in bipe-shared
/// tools/lib/registries.mjs and test-vectors/work-notes.json.
public enum BipeWorkNotes {
    /// One note that the apps show on a customer chat.
    public struct Note: Equatable, Sendable {
        public var roomId: String
        public var body: String
        /// The seat that wrote the note, when it is a seat of the account; else nil.
        public var seatId: String?

        public init(roomId: String, body: String, seatId: String?) {
            self.roomId = roomId
            self.body = body
            self.seatId = seatId
        }
    }

    /// The seat that sent one reply to a customer.
    public struct ReplyBy: Equatable, Sendable {
        public var roomId: String
        public var eventId: String
        public var seatId: String

        public init(roomId: String, eventId: String, seatId: String) {
            self.roomId = roomId
            self.eventId = eventId
            self.seatId = seatId
        }
    }

    /// The result of a rule: the value, or the reason the apps do not show the event.
    public enum Result<Value: Equatable & Sendable>: Equatable, Sendable {
        case show(Value)
        case hide(reason: String)

        public var value: Value? {
            if case .show(let value) = self { return value }
            return nil
        }
    }

    /// The notes room ID from the content of the global account data chat.bipe.work_notes_room ({"room_id": ...}).
    /// The same shape as the drafts room. nil when absent or malformed.
    public static func roomId(accountDataJson json: String?) -> String? {
        BipeAssistantDrafts.roomId(accountDataJson: json)
    }

    /// The account data content for a notes room ID.
    public static func accountDataContent(roomId: String) -> String {
        json(["room_id": roomId])
    }

    /// The m.room.message content of a note on the customer chat roomId, written by seatId (this device's seat).
    public static func noteContent(body: String, roomId: String, seatId: String?) -> String {
        var field: [String: Any] = ["room_id": roomId]
        if let seatId { field["seat_id"] = seatId }
        return json(["msgtype": "m.text", "body": body, BipeRegistries.workNoteField: field])
    }

    /// The m.room.message content of a reply-by notice: seatId sent eventId in the customer chat roomId.
    public static func replyByContent(roomId: String, eventId: String, seatId: String) -> String {
        json(["msgtype": "m.notice", "body": BipeRegistries.workReplyByBody,
              BipeRegistries.workReplyByField: ["room_id": roomId, "event_id": eventId, "seat_id": seatId]])
    }

    /// Parses a raw event JSON. sender (EventTimelineItem.sender) replaces the JSON sender when given, and redacted marks
    /// a redacted timeline item.
    private static func event(json: String?, sender: String?, redacted: Bool) -> [String: Any]? {
        guard let data = json?.data(using: .utf8),
              var event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        if let sender { event["sender"] = sender }
        if redacted || ((event["unsigned"] as? [String: Any])?["redacted_because"] != nil) { event["redacted"] = true }
        return event
    }

    /// workNoteFromEvent for the raw JSON of a notes room event.
    public static func noteFromEventJson(_ json: String?, sender: String? = nil, redacted: Bool = false,
                                         ownUserId: String, joinedRoomIds: Set<String>, seatIds: Set<String>) -> Result<Note> {
        guard let event = event(json: json, sender: sender, redacted: redacted) else { return .hide(reason: "type") }
        return noteFromEvent(event, ownUserId: ownUserId, joinedRoomIds: joinedRoomIds, seatIds: seatIds)
    }

    /// workNoteFromEvent: event is { sender, type, redacted?, content }, an event of the notes room. seatIds: every
    /// seat of GET /v1/me/business/seats; a seat_id outside it gives a note with no author.
    public static func noteFromEvent(_ event: [String: Any], ownUserId: String, joinedRoomIds: Set<String>,
                                     seatIds: Set<String>) -> Result<Note> {
        guard event["type"] as? String == "m.room.message" else { return .hide(reason: "type") }
        if let redacted = event["redacted"] as? Bool, redacted { return .hide(reason: "redacted") }
        guard event["sender"] as? String == ownUserId else { return .hide(reason: "sender") }
        guard let content = event["content"] as? [String: Any], content["msgtype"] as? String == "m.text" else {
            return .hide(reason: "content")
        }
        guard let field = content[BipeRegistries.workNoteField] as? [String: Any] else { return .hide(reason: "field") }
        guard let roomId = field["room_id"] as? String, roomId.hasPrefix("!") else { return .hide(reason: "room_id") }
        guard joinedRoomIds.contains(roomId) else { return .hide(reason: "not_joined") }
        guard let body = content["body"] as? String,
              !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .hide(reason: "body") }
        // JavaScript String.length counts UTF-16 code units.
        guard body.utf16.count <= BipeRegistries.workNoteBodyMaxLength else { return .hide(reason: "body_too_long") }
        let seatId = (field["seat_id"] as? String).flatMap { seatIds.contains($0) ? $0 : nil }
        return .show(Note(roomId: roomId, body: body, seatId: seatId))
    }

    /// replyByFromEvent for the raw JSON of a notes room event. seatIds: every seat of GET /v1/me/business/seats.
    public static func replyByFromEventJson(_ json: String?, sender: String? = nil, redacted: Bool = false,
                                            ownUserId: String, seatIds: Set<String>) -> Result<ReplyBy> {
        guard let event = event(json: json, sender: sender, redacted: redacted) else { return .hide(reason: "type") }
        return replyByFromEvent(event, ownUserId: ownUserId, seatIds: seatIds)
    }

    /// replyByFromEvent: event is { sender, type, redacted?, content }, an event of the notes room.
    public static func replyByFromEvent(_ event: [String: Any], ownUserId: String, seatIds: Set<String>) -> Result<ReplyBy> {
        guard event["type"] as? String == "m.room.message" else { return .hide(reason: "type") }
        if let redacted = event["redacted"] as? Bool, redacted { return .hide(reason: "redacted") }
        guard event["sender"] as? String == ownUserId else { return .hide(reason: "sender") }
        guard let message = event["content"] as? [String: Any], message["msgtype"] as? String == "m.notice" else {
            return .hide(reason: "content")
        }
        guard let content = message[BipeRegistries.workReplyByField] as? [String: Any] else { return .hide(reason: "field") }
        guard let roomId = content["room_id"] as? String, roomId.hasPrefix("!") else { return .hide(reason: "room_id") }
        guard let eventId = content["event_id"] as? String, eventId.hasPrefix("$") else { return .hide(reason: "event_id") }
        guard let seatId = content["seat_id"] as? String, seatIds.contains(seatId) else { return .hide(reason: "seat_id") }
        return .show(ReplyBy(roomId: roomId, eventId: eventId, seatId: seatId))
    }

    private static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }
}
