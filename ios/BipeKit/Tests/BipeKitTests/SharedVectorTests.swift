// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Every case in bipe-shared/test-vectors must pass (ADR 0031, rule 4). Android runs the same files.
struct SharedVectorTests {
    @Test
    func photoMatch() throws {
        let cases = try SharedVectors.array("photo-match.json", key: "cases")
        #expect(!cases.isEmpty)
        for vector in cases {
            let name = vector["name"] as? String ?? ""
            let gridA = try #require(vector["gridA"] as? [[NSNumber]]).map { $0.map(\.uint8Value) }
            let gridB = try #require(vector["gridB"] as? [[NSNumber]]).map { $0.map(\.uint8Value) }
            let hashA = try #require(BipePhotoMatch.hash(gray: gridA))
            let hashB = try #require(BipePhotoMatch.hash(gray: gridB))
            #expect(hashA == vector["hashA"] as? String, "photo match \(name)")
            #expect(hashB == vector["hashB"] as? String, "photo match \(name)")
            #expect(BipePhotoMatch.distance(hashA, hashB) == (vector["distance"] as? NSNumber)?.intValue, "photo match \(name)")
            #expect(BipePhotoMatch.matches(hashA, hashB) == vector["matches"] as? Bool, "photo match \(name)")
        }
    }

    @Test
    func relationshipNames() throws {
        let vectors = try SharedVectors.array("relationship-names.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            #expect(BipeRelationshipNames.warning(savedName: vector["savedName"] as? String,
                verifiedName: vector["verifiedName"] as? String,
                nicknameConfirmed: vector["nicknameConfirmed"] as? Bool ?? false) == vector["expected"] as? String,
                "relationship name \(vector["name"] ?? "")")
        }
    }

    @Test
    func dmSelection() throws {
        for vector in try SharedVectors.array("dm-selection.json", key: "cases") {
            let raw = try #require(vector["candidates"] as? [[String: Any]])
            let candidates = try raw.map { item in
                BipeDmCandidate(roomId: try #require(item["roomId"] as? String),
                    peerUserId: try #require(item["peerUserId"] as? String),
                    latestActivityMs: (item["latestActivityMs"] as? NSNumber)?.int64Value,
                    eligible: try #require(item["eligible"] as? Bool), cleared: item["cleared"] as? Bool ?? false)
            }
            let expected = try #require(vector["expected"] as? [String: String])
            #expect(BipeDmSelection.newestRooms(candidates) == expected)
            #expect(BipeDmSelection.newestRooms(Array(candidates.reversed())) == expected)
            if let fallback = vector["includingCleared"] as? [String: String] {
                #expect(BipeDmSelection.newestRooms(candidates, includingCleared: true) == fallback)
            }
        }
    }

    @Test
    func trustFacts() throws {
        let vectors = try SharedVectors.array("trust-facts.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let inputData = try JSONSerialization.data(withJSONObject: #require(vector["input"]))
            let expectedData = try JSONSerialization.data(withJSONObject: #require(vector["expected"]))
            let input = try JSONDecoder().decode(BipeTrustInput.self, from: inputData)
            let expected = try JSONDecoder().decode(BipeTrustFacts.self, from: expectedData)
            #expect(BipeTrustFacts.evaluate(input) == expected, "trust facts \(vector["name"] ?? "")")
        }
    }
    
    @Test
    func normalization() throws {
        for vector in try SharedVectors.array("normalization.json", key: "vectors") {
            let input = try #require(vector["input"] as? String)
            #expect(NameNormalizer.normalize(input) == vector["normalized"] as? String, "normalize(\(input))")
            #expect(NameNormalizer.reservedCategory(for: input)?.rawValue == vector["reserved"] as? String, "reserved(\(input))")
        }
    }
    
    @Test
    func scamDetection() throws {
        for vector in try SharedVectors.array("scam-detection.json", key: "vectors") {
            let message = try #require(vector["message"] as? String)
            #expect(ScamDetector.matchIDs(in: message) == vector["matches"] as? [String], "matches(\(message))")
        }
    }
    
    @Test
    func scamWarnings() throws {
        for vector in try SharedVectors.array("scam-warnings.json", key: "vectors") {
            let message = try #require(vector["message"] as? String)
            let kind = ScamDetector.warningKind(forMatchIDs: ScamDetector.matchIDs(in: message), message: message)
            #expect(kind?.rawValue == vector["kind"] as? String, "kind(\(message))")
        }
    }
    
    @Test
    func pixCheck() throws {
        let vectors = try SharedVectors.array("pix-check.json", key: "vectors")
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let text = try #require(vector["text"] as? String)
            let result = PixCheck.check(text)
            let label = "pixCheck(\(text.prefix(40)))"
            
            if let expected = vector["code"] as? [String: Any] {
                let code = try #require(result.code, "\(label) code")
                #expect(code.status.rawValue == expected["status"] as? String, "\(label) status")
                if code.status != .malformed {
                    #expect(code.dynamic == expected["dynamic"] as? Bool, "\(label) dynamic")
                    #expect(code.singleUse == expected["singleUse"] as? Bool, "\(label) singleUse")
                    #expect(code.key == expected["key"] as? String, "\(label) key")
                    #expect(code.keyType?.rawValue == expected["keyType"] as? String, "\(label) keyType")
                    #expect(code.url == expected["url"] as? String, "\(label) url")
                    #expect(code.name == expected["name"] as? String, "\(label) name")
                    #expect(code.city == expected["city"] as? String, "\(label) city")
                    #expect(code.amount == expected["amount"] as? String, "\(label) amount")
                    #expect(code.txid == expected["txid"] as? String, "\(label) txid")
                    #expect(code.info == expected["info"] as? String, "\(label) info")
                }
            } else {
                #expect(result.code == nil, "\(label) code")
            }
            
            let keys = try #require(vector["keys"] as? [[String: Any]])
            #expect(result.keys.map(\.value) == keys.map { $0["value"] as? String ?? "" }, "\(label) keys")
            #expect(result.keys.map { $0.types.map(\.rawValue) } == keys.map { $0["types"] as? [String] ?? [] }, "\(label) key types")
            #expect(result.warning?.rawValue == vector["warning"] as? String, "\(label) warning")
        }
    }
    
    /// The client checks length and characters only. Reserved names and lookalikes are the server's call.
    @Test
    func handles() throws {
        let localProblems: Set = ["too_short", "too_long", "invalid_characters"]
        for vector in try SharedVectors.array("handles.json", key: "cases") {
            let input = try #require(vector["input"] as? String)
            let expected = (vector["problem"] as? String).flatMap { localProblems.contains($0) ? $0 : nil }
            #expect(BipeHandle.localProblem(for: input)?.rawValue == expected, "localProblem(\(input))")
        }
    }
    
    @Test
    func handleSuggestions() throws {
        for vector in try SharedVectors.array("handle-suggestions.json", key: "cases") {
            let name = try #require(vector["name"] as? String)
            #expect(BipeHandle.suggestions(for: name) == vector["suggestions"] as? [String], "suggestions(\(name))")
        }
    }

    @Test
    func pinReminderSchedule() throws {
        for vector in try SharedVectors.array("onboarding-schedule.json", key: "pinReminder") {
            let step = try #require(vector["step"] as? Int)
            let result = try #require((vector["result"] as? String).flatMap(BipeOnboardingSchedule.PinResult.init(rawValue:)))
            let next = BipeOnboardingSchedule.pinReminderNext(step: step, result: result)
            #expect(next.step == vector["nextStep"] as? Int, "pinReminderNext(\(step), \(result)) step")
            #expect(next.gapDays == vector["gapDays"] as? Int, "pinReminderNext(\(step), \(result)) gapDays")
        }
    }

    @Test
    func inviteCodes() throws {
        for vector in try SharedVectors.array("invite-codes.json", key: "vectors") {
            let input = try #require(vector["input"] as? String)
            #expect(BipeInviteLink.normalizedCode(input) == vector["code"] as? String, "normalizedCode(\(input))")
        }
    }
    
    @Test
    func pins() throws {
        let cases = try SharedVectors.array("pins.json", key: "cases")
        #expect(!cases.isEmpty)
        for vector in cases {
            let input = try #require(vector["input"] as? String)
            #expect(BipePIN.problem(for: input)?.rawValue == vector["problem"] as? String, "pinProblem(\(input))")
        }
    }
    
    @Test
    func badges() throws {
        let file = try SharedVectors.object("badges.json")
        let now = try Date(timeIntervalSince1970: #require(file["now"] as? Double))
        let keys = try #require(file["publicKeys"] as? [String: String])
        let verifier = try BadgeVerifier(trustedKeys: keys.mapValues { try #require(Data(base64URL: $0)) })
        for vector in try #require(file["vectors"] as? [[String: Any]]) {
            let name = try #require(vector["name"] as? String)
            let envelope = try JSONSerialization.data(withJSONObject: #require(vector["envelope"]))
            let result = try verifier.verify(envelopeJSON: envelope,
                                             sender: #require(vector["sender"] as? String),
                                             senderDevice: #require(vector["senderDevice"] as? String),
                                             senderMasterKey: vector["senderMasterKey"] as? String,
                                             now: now)
            let outcome = switch result {
            case .valid: "valid"
            case .invalid(let failure): failure.rawValue
            }
            #expect(outcome == vector["expect"] as? String, "badge \(name)")
        }
    }
    
    @Test
    func profileFacts() throws {
        let file = try SharedVectors.object("profile-facts.json")
        let nowText = try #require(file["now"] as? String)
        let now = try #require(BipeProfileFactLines.parse(nowText))
        let vectors = try #require(file["vectors"] as? [[String: Any]])
        #expect(!vectors.isEmpty)
        for vector in vectors {
            let name = vector["name"] as? String ?? ""
            let raw = try #require(vector["facts"] as? [String: Any])
            let facts = BipeProfileFacts(userId: "@a:bipe.net.br",
                                         createdAt: raw["created_at"] as? String,
                                         nameChanges: raw["name_changes"] as? [String] ?? [],
                                         photoChanges: raw["photo_changes"] as? [String] ?? [])
            let result = BipeProfileFactLines(facts: facts, now: now)
            let expected = try #require(vector["expected"] as? [String: Any])
            let lines = try #require(expected["lines"] as? [[String: Any]])
            #expect(result.notice == expected["notice"] as? Bool, "profile facts \(name)")
            #expect(result.lines.count == lines.count, "profile facts \(name)")
            for (line, want) in zip(result.lines, lines) {
                #expect(line.fact.rawValue == want["fact"] as? String, "profile facts \(name)")
                #expect(line.count == (want["count"] as? NSNumber)?.intValue, "profile facts \(name)")
                let atText = try #require(want["at"] as? String)
                let at = try #require(BipeProfileFactLines.parse(atText))
                #expect(BipeProfileFactLines.milliseconds(line.at) == BipeProfileFactLines.milliseconds(at), "profile facts \(name)")
            }
        }
    }
    
    @Test
    func clearedChats() throws {
        let file = try SharedVectors.object("cleared-chats.json")
        #expect(file["event_type"] as? String == ClearedChats.eventType)
        
        let markers = try #require(file["marker"] as? [[String: Any]])
        #expect(!markers.isEmpty)
        for vector in markers {
            let roomID = try #require(vector["room_id"] as? String)
            let marker = ClearedChats(content: vector["content"]).marker(for: roomID)
            #expect(marker == SharedVectors.timestamp(vector["expected"]), "marker \(vector["name"] ?? "")")
        }
        
        let hidden = try #require(file["hidden"] as? [[String: Any]])
        #expect(!hidden.isEmpty)
        for vector in hidden {
            let isHidden = ClearedChats.isHidden(marker: SharedVectors.timestamp(vector["marker"]),
                                                 latestTs: SharedVectors.timestamp(vector["latest_ts"]))
            #expect(isHidden == vector["expected"] as? Bool, "hidden \(vector["name"] ?? "")")
        }
        
        let keeps = try #require(file["keeps"] as? [[String: Any]])
        #expect(!keeps.isEmpty)
        for vector in keeps {
            let eventTs = try #require(SharedVectors.timestamp(vector["event_ts"]))
            let keepsEvent = ClearedChats.keepsEvent(marker: SharedVectors.timestamp(vector["marker"]), eventTs: eventTs)
            #expect(keepsEvent == vector["expected"] as? Bool, "keeps \(vector["name"] ?? "")")
        }
        
        let clears = try #require(file["clear"] as? [[String: Any]])
        #expect(!clears.isEmpty)
        for vector in clears {
            let name = vector["name"] ?? ""
            let roomID = try #require(vector["room_id"] as? String)
            let ts = try #require(SharedVectors.timestamp(vector["ts"]))
            let expected = try #require(vector["expected"] as? [String: Any])
            let content = ClearedChats.content(clearing: vector["content"], roomID: roomID, at: ts)
            #expect(NSDictionary(dictionary: content).isEqual(to: expected), "clear \(name)")
            
            // The JSON string round trip that the app writes to account data.
            var input: String?
            if let raw = vector["content"] {
                input = try String(data: JSONSerialization.data(withJSONObject: raw, options: [.fragmentsAllowed]), encoding: .utf8)
            }
            let json = try #require(ClearedChats.json(clearing: input, roomID: roomID, at: ts), "clear json \(name)")
            let written = try #require(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
            #expect(NSDictionary(dictionary: written).isEqual(to: expected), "clear json \(name)")
        }
    }
}

/// Loads bipe-shared/test-vectors next to this repo. BIPE_SHARED overrides the bipe-shared path.
enum SharedVectors {
    static var directory: URL {
        if let path = ProcessInfo.processInfo.environment["BIPE_SHARED"] {
            return URL(filePath: path).appending(path: "test-vectors")
        }
        // Tests/BipeKitTests/<file> -> BipeKit -> ios -> bipe-app -> the folder that holds bipe-shared
        return URL(filePath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "bipe-shared/test-vectors")
    }
    
    static func object(_ name: String) throws -> [String: Any] {
        let data = try Data(contentsOf: directory.appending(path: name))
        return try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    
    static func array(_ name: String, key: String) throws -> [[String: Any]] {
        try #require(try object(name)[key] as? [[String: Any]])
    }
    
    /// A millisecond time from a vector. JSON `null` (NSNull) and a missing key are `nil`.
    static func timestamp(_ value: Any?) -> UInt64? {
        (value as? NSNumber)?.uint64Value
    }
}

private extension Data {
    init?(base64URL string: String) {
        var base64 = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64.append(String(repeating: "=", count: (4 - base64.count % 4) % 4))
        self.init(base64Encoded: base64)
    }
}
