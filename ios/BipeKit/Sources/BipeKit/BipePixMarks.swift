// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Pix request marks (ADR 0046, bipe-chat/bipe#130; registries/pix-marks.json): the payer marks "enviei", the requester
/// marks "recebi" or "não recebi". Bipe moves no money (ADR 0012); a line is paid only after "recebi". Matches
/// pixMarkLines and pixTxid in bipe-shared tools/lib/pix.mjs and test-vectors/pix-marks.json.
public enum BipePixMarks {
    public enum State: String, Sendable {
        case issued
        case sent
        case received
        case notReceived = "not_received"
    }

    /// The marks a person can send: every state but issued.
    public enum Mark: String, Sendable {
        case sent
        case received
        case notReceived = "not_received"
    }

    public struct Card: Equatable, Sendable {
        public let eventId: String
        public let requester: String
        public let payers: [String]

        public init(eventId: String, requester: String, payers: [String]) {
            self.eventId = eventId
            self.requester = requester
            self.payers = payers
        }
    }

    /// A mark event: its ID, sender, origin_server_ts and content (decoded JSON).
    public struct Event {
        public let eventId: String
        public let sender: String
        public let ts: Int64
        public let content: Any?

        public init(eventId: String, sender: String, ts: Int64, content: Any?) {
            self.eventId = eventId
            self.sender = sender
            self.ts = ts
            self.content = content
        }
    }

    public struct HistoryEntry: Equatable, Sendable {
        public let eventId: String
        public let mark: Mark
    }

    /// One payer's line of the card.
    public struct Line: Equatable, Sendable {
        public let payer: String
        public internal(set) var state: State = .issued
        /// The payer's claim from the bank receipt. Bipe cannot check that it is real.
        public internal(set) var e2eId: String?
        public var isPaid: Bool { state == .received }
        public internal(set) var history: [HistoryEntry] = []
    }

    public struct Result: Equatable, Sendable {
        public let lines: [Line]
        public let rejected: [String]
    }

    /// The lines of a request card from its mark events. Marks that break a rule are rejected and change nothing.
    public static func lines(card: Card, events: [Event]) -> Result {
        var lines = card.payers.map { Line(payer: $0) }
        var rejected: [String] = []
        let ordered = events.sorted { $0.ts != $1.ts ? $0.ts < $1.ts : $0.eventId.utf16.lexicographicallyPrecedes($1.eventId.utf16) }
        for event in ordered {
            guard let hit = accepted(card: card, event: event, lines: lines) else {
                rejected.append(event.eventId)
                continue
            }
            let (index, mark, e2eId) = hit
            lines[index].state = State(rawValue: mark.rawValue) ?? .issued
            if mark == .sent { lines[index].e2eId = e2eId }
            lines[index].history.append(HistoryEntry(eventId: event.eventId, mark: mark))
        }
        return Result(lines: lines, rejected: rejected)
    }

    /// The content of a mark event, ready for Room.sendRaw with eventType BipeRegistries.pixMarkEventType.
    /// nil when the E2E ID is given and malformed, or given on another mark than sent.
    public static func content(cardEventId: String, mark: Mark, payer: String?, e2eId: String?) -> [String: Any]? {
        if let e2eId, mark != .sent || !isValidE2eId(e2eId) { return nil }
        var content: [String: Any] = [
            "m.relates_to": ["rel_type": BipeRegistries.pixMarkRelType, "event_id": cardEventId],
            "mark": mark.rawValue,
        ]
        if let payer { content["payer"] = payer }
        if let e2eId { content["e2e_id"] = e2eId }
        return content
    }

    /// The format of a Pix end-to-end ID: E, the 8-digit ISPB, 23 letters or digits (BipeRegistries.pixE2eIdPattern).
    public static func isValidE2eId(_ id: String) -> Bool {
        let scalars = Array(id.unicodeScalars)
        guard scalars.count == 32, scalars[0] == "E" else { return false }
        let digits = scalars[1...8].allSatisfy { ("0"..."9").contains($0) }
        let rest = scalars[9...].allSatisfy(isAsciiAlphanumeric)
        return digits && rest
    }

    /// The BR Code txid of a card: the request ID with ASCII letters and digits only, at most pixTxidMaxLength.
    public static func txid(requestId: String) -> String {
        var txid = ""
        txid.unicodeScalars.append(contentsOf: requestId.unicodeScalars.filter(isAsciiAlphanumeric).prefix(BipeRegistries.pixTxidMaxLength))
        return txid
    }

    private static func isAsciiAlphanumeric(_ scalar: Unicode.Scalar) -> Bool {
        ("0"..."9").contains(scalar) || ("a"..."z").contains(scalar) || ("A"..."Z").contains(scalar)
    }

    private static let allowedFrom: [Mark: Set<State>] = [
        .sent: [.issued, .sent, .notReceived],
        .received: [.issued, .sent, .notReceived],
        .notReceived: [.sent, .received],
    ]

    private static func accepted(card: Card, event: Event, lines: [Line]) -> (Int, Mark, String?)? {
        guard let content = event.content as? [String: Any],
              let relation = content["m.relates_to"] as? [String: Any],
              relation["rel_type"] as? String == BipeRegistries.pixMarkRelType,
              relation["event_id"] as? String == card.eventId,
              let raw = content["mark"] as? String, let mark = Mark(rawValue: raw) else { return nil }
        let payerField = content["payer"]
        if payerField != nil, !(payerField is String) { return nil }
        let namedPayer = payerField as? String
        var e2eId: String?
        if let value = content["e2e_id"] {
            guard mark == .sent, let id = value as? String, isValidE2eId(id) else { return nil }
            e2eId = id
        }
        let payer: String?
        if mark == .sent {
            if let namedPayer, namedPayer != event.sender { return nil }
            payer = event.sender
        } else {
            guard event.sender == card.requester else { return nil }
            payer = namedPayer ?? (lines.count == 1 ? lines[0].payer : nil)
        }
        guard let index = lines.firstIndex(where: { $0.payer == payer }),
              allowedFrom[mark]?.contains(lines[index].state) == true else { return nil }
        return (index, mark, e2eId)
    }
}
