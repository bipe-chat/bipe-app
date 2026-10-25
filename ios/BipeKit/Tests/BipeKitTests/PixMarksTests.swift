// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// test-vectors/pix-marks.json (ADR 0046, bipe-chat/bipe#130). Android runs the same file.
struct PixMarksTests {
    @Test
    func lines() throws {
        let vectors = try SharedVectors.array("pix-marks.json", key: "lines")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let name = vector["name"] as? String ?? ""
            let rawCard = try #require(vector["card"] as? [String: Any])
            let card = BipePixMarks.Card(eventId: try #require(rawCard["event_id"] as? String),
                                         requester: try #require(rawCard["requester"] as? String),
                                         payers: try #require(rawCard["payers"] as? [String]))
            let events = try #require(vector["events"] as? [[String: Any]]).map { raw in
                BipePixMarks.Event(eventId: raw["event_id"] as? String ?? "",
                                   sender: raw["sender"] as? String ?? "",
                                   ts: (raw["ts"] as? NSNumber)?.int64Value ?? 0,
                                   content: raw["content"])
            }
            let expected = try #require(vector["expected"] as? [String: Any])
            let result = BipePixMarks.lines(card: card, events: events)
            #expect(result.rejected == expected["rejected"] as? [String], "pix marks \(name): rejected")
            let lines = try #require(expected["lines"] as? [[String: Any]])
            #expect(result.lines.count == lines.count, "pix marks \(name): lines")
            for (line, want) in zip(result.lines, lines) {
                #expect(line.payer == want["payer"] as? String, "pix marks \(name): payer")
                #expect(line.state.rawValue == want["state"] as? String, "pix marks \(name): state")
                #expect(line.e2eId == want["e2e_id"] as? String, "pix marks \(name): e2e_id")
                #expect(line.isPaid == want["paid"] as? Bool, "pix marks \(name): paid")
                let history = (want["history"] as? [[String: String]] ?? []).map { "\($0["event_id"] ?? "") \($0["mark"] ?? "")" }
                #expect(line.history.map { "\($0.eventId) \($0.mark.rawValue)" } == history, "pix marks \(name): history")
            }
        }
    }

    @Test
    func txid() throws {
        for vector in try SharedVectors.array("pix-marks.json", key: "txid") {
            #expect(BipePixMarks.txid(requestId: vector["request_id"] as? String ?? "") == vector["expected"] as? String)
        }
    }

    @Test
    func contentRoundTrip() throws {
        let content = try #require(BipePixMarks.content(cardEventId: "$card", mark: .sent, payer: nil,
                                                        e2eId: "E12345678202610251200abcDEF12345"))
        let card = BipePixMarks.Card(eventId: "$card", requester: "@loja:x", payers: ["@ana:x"])
        let result = BipePixMarks.lines(card: card, events: [BipePixMarks.Event(eventId: "$m", sender: "@ana:x", ts: 1, content: content)])
        #expect(result.lines.first?.state == .sent)
        #expect(BipePixMarks.content(cardEventId: "$card", mark: .received, payer: nil, e2eId: "E12345678202610251200abcDEF12345") == nil)
        #expect(BipePixMarks.content(cardEventId: "$card", mark: .sent, payer: nil, e2eId: "E1") == nil)
    }
}
