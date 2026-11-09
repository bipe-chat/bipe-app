// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Pix request marks (ADR 0046, bipe-chat/bipe#130; registries/pix-marks.json): the payer marks "enviei", the requester
/// marks "recebi" or "não recebi". Bipe moves no money (ADR 0012); a line is paid only after "recebi". Matches
/// pixMarkLines and pixTxid in bipe-shared tools/lib/pix.mjs and test-vectors/pix-marks.json. The rules run in
/// bipe-core (`pixMarkLines`, ADR 0267).
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
        let result = pixMarkLines(
            card: PixMarkCard(eventId: card.eventId, requester: card.requester, payers: card.payers),
            events: events.map { PixMarkEvent(eventId: $0.eventId, sender: $0.sender, ts: $0.ts, contentJson: json($0.content)) }
        )
        let lines = result.lines.map { line in
            Line(payer: line.payer, state: State(line.state), e2eId: line.e2eId,
                 history: line.history.map { HistoryEntry(eventId: $0.eventId, mark: Mark($0.mark)) })
        }
        return Result(lines: lines, rejected: result.rejected)
    }

    /// The content of a mark event, ready for Room.sendRaw with eventType BipeRegistries.pixMarkEventType.
    /// nil when the E2E ID is given and malformed, or given on another mark than sent.
    public static func content(cardEventId: String, mark: Mark, payer: String?, e2eId: String?) -> [String: Any]? {
        guard let text = pixMarkContent(cardEventId: cardEventId, mark: mark.core, payer: payer, e2eId: e2eId),
              let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /// The format of a Pix end-to-end ID: E, the 8-digit ISPB, 23 letters or digits (BipeRegistries.pixE2eIdPattern).
    public static func isValidE2eId(_ id: String) -> Bool {
        pixE2eIdValid(id: id)
    }

    /// The BR Code txid of a card: the request ID with ASCII letters and digits only, at most pixTxidMaxLength.
    public static func txid(requestId: String) -> String {
        pixTxid(requestId: requestId)
    }

    /// Content that is not a JSON object is "null": the core rejects that mark.
    private static func json(_ content: Any?) -> String {
        guard let content, JSONSerialization.isValidJSONObject(content),
              let data = try? JSONSerialization.data(withJSONObject: content) else { return "null" }
        return String(decoding: data, as: UTF8.self)
    }
}

extension BipePixMarks.State {
    init(_ state: PixMarkState) {
        switch state {
        case .issued: self = .issued
        case .sent: self = .sent
        case .received: self = .received
        case .notReceived: self = .notReceived
        }
    }
}

extension BipePixMarks.Mark {
    init(_ mark: PixMark) {
        switch mark {
        case .sent: self = .sent
        case .received: self = .received
        case .notReceived: self = .notReceived
        }
    }

    var core: PixMark {
        switch self {
        case .sent: .sent
        case .received: .received
        case .notReceived: .notReceived
        }
    }
}
