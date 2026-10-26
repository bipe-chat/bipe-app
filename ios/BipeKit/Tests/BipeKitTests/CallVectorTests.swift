// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// bipe-shared test-vectors/calls (bipe-chat/bipe#758). Android runs the same file in BipeCallTest.kt.
struct CallVectorTests {
    private let file = "calls/matrixrtc.json"

    /// JSON round trip, so numbers and nested types compare the way the wire shows them.
    private func json(_ object: Any) throws -> NSDictionary {
        let data = try JSONSerialization.data(withJSONObject: object)
        return try #require(try JSONSerialization.jsonObject(with: data) as? NSDictionary)
    }

    private func hex(_ text: String) -> Data {
        var data = Data()
        var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            data.append(UInt8(text[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    @Test
    func stateKeys() throws {
        let cases = try SharedVectors.array(file, key: "stateKeys")
        #expect(!cases.isEmpty)
        for vector in cases {
            let user = try #require(vector["userId"] as? String)
            let device = try #require(vector["deviceId"] as? String)
            #expect(BipeCall.stateKey(userId: user, deviceId: device) == vector["stateKey"] as? String)
            #expect(BipeCall.livekitIdentity(userId: user, deviceId: device) == vector["livekitIdentity"] as? String)
        }
    }

    @Test
    func memberships() throws {
        for vector in try SharedVectors.array(file, key: "memberships") {
            let content = BipeCall.membershipContent(deviceId: try #require(vector["deviceId"] as? String),
                roomId: try #require(vector["roomId"] as? String), focusUrl: try #require(vector["focusUrl"] as? String),
                createdTs: try #require((vector["createdTs"] as? NSNumber)?.int64Value), video: vector["video"] as? Bool == true)
            let expected = try #require(vector["content"] as? [String: Any])
            #expect(try json(content).isEqual(to: expected), "\(vector["name"] ?? "")")
        }
    }

    @Test
    func sfuRequests() throws {
        for vector in try SharedVectors.array(file, key: "sfuRequests") {
            let raw = try #require(vector["openId"] as? [String: Any])
            let openId = BipeCallOpenId(accessToken: try #require(raw["accessToken"] as? String),
                tokenType: try #require(raw["tokenType"] as? String), matrixServerName: try #require(raw["matrixServerName"] as? String),
                expiresInSeconds: try #require((raw["expiresInSeconds"] as? NSNumber)?.uint64Value))
            let body = BipeCall.sfuRequestBody(roomId: try #require(vector["roomId"] as? String),
                deviceId: try #require(vector["deviceId"] as? String), openId: openId)
            let expected = try #require(vector["body"] as? [String: Any])
            #expect(try json(body).isEqual(to: expected))
            let response = try #require(vector["response"] as? [String: Any])
            let answer = try #require(BipeCall.sfuAnswer(response))
            #expect(answer.url == "wss://rtc.bipe.net.br")
        }
    }

    @Test
    func keyEvents() throws {
        for vector in try SharedVectors.array(file, key: "keyEvents") {
            let content = BipeCall.keyEventContent(index: try #require((vector["index"] as? NSNumber)?.int32Value),
                key: hex(try #require(vector["keyHex"] as? String)), deviceId: try #require(vector["deviceId"] as? String),
                sentTs: try #require((vector["sentTs"] as? NSNumber)?.int64Value))
            let expected = try #require(vector["content"] as? [String: Any])
            #expect(try json(content).isEqual(to: expected), "\(vector["name"] ?? "")")
        }
        for vector in try SharedVectors.array(file, key: "keyReads") {
            let keys = BipeCall.readKeys(sender: try #require(vector["sender"] as? String),
                                         content: try #require(vector["content"] as? [String: Any]))
            let expected = try #require(vector["keys"] as? [[String: Any]]).map { item in
                BipeCallKey(identity: item["identity"] as? String ?? "", index: (item["index"] as? NSNumber)?.int32Value ?? -1,
                            key: hex(item["keyHex"] as? String ?? ""))
            }
            #expect(keys == expected, "\(vector["name"] ?? "")")
        }
    }

    @Test
    func foci() throws {
        for vector in try SharedVectors.array(file, key: "foci") {
            let wellKnown = try #require(vector["wellKnown"] as? [String: Any])
            #expect(BipeCall.focusUrl(wellKnown: wellKnown) == vector["focusUrl"] as? String, "\(vector["name"] ?? "")")
        }
    }

    @Test
    func members() throws {
        let members = try #require(try SharedVectors.object(file)["members"] as? [String: Any])
        let now = try #require((members["now"] as? NSNumber)?.int64Value)
        var active: [BipeCallMember] = []
        for vector in try #require(members["events"] as? [[String: Any]]) {
            let member = BipeCall.member(sender: try #require(vector["sender"] as? String),
                stateKey: try #require(vector["stateKey"] as? String),
                originServerTs: try #require((vector["originServerTs"] as? NSNumber)?.int64Value),
                content: try #require(vector["content"] as? [String: Any]), now: now)
            if let expected = vector["member"] as? [String: Any] {
                #expect(member == BipeCallMember(userId: expected["userId"] as? String ?? "", deviceId: expected["deviceId"] as? String ?? "",
                    createdTs: (expected["createdTs"] as? NSNumber)?.int64Value ?? 0, focusUrl: expected["focusUrl"] as? String),
                    "\(vector["name"] ?? "")")
            } else {
                #expect(member == nil, "\(vector["name"] ?? "")")
            }
            if let member { active.append(member) }
        }
        let oldest = try #require(members["oldestFocus"] as? [String: String])
        let own = try #require(oldest["own"])
        #expect(BipeCall.oldestFocus(members: active, own: own) == oldest["focusUrl"])
        #expect(BipeCall.oldestFocus(members: [], own: own) == oldest["alone"])
    }
}

/// bipe-shared test-vectors/calls/ringing.json (bipe-chat/bipe#759). Android runs the same file in BipeCallRingTest.kt.
struct CallRingVectorTests {
    private let file = "calls/ringing.json"

    private func json(_ object: Any) throws -> NSDictionary {
        let data = try JSONSerialization.data(withJSONObject: object)
        return try #require(try JSONSerialization.jsonObject(with: data) as? NSDictionary)
    }

    @Test
    func ringContents() throws {
        let cases = try SharedVectors.array(file, key: "ringContents")
        #expect(!cases.isEmpty)
        for vector in cases {
            let content = BipeCallRing.content(notificationType: try #require(vector["notificationType"] as? String),
                senderTs: try #require((vector["senderTs"] as? NSNumber)?.int64Value),
                membershipEventId: try #require(vector["membershipEventId"] as? String),
                userIds: try #require(vector["userIds"] as? [String]), video: vector["video"] as? Bool == true)
            let expected = try #require(vector["content"] as? [String: Any])
            #expect(try json(content).isEqual(to: expected), "\(vector["name"] ?? "")")
        }
    }

    @Test
    func ringReads() throws {
        for vector in try SharedVectors.array(file, key: "ringReads") {
            let read = BipeCallRing.read(content: try #require(vector["content"] as? [String: Any]),
                sender: try #require(vector["sender"] as? String),
                originServerTs: try #require((vector["originServerTs"] as? NSNumber)?.int64Value),
                ownUserId: try #require(vector["ownUserId"] as? String), now: try #require((vector["now"] as? NSNumber)?.int64Value))
            let expected = BipeCallRingRead(ring: vector["ring"] as? Bool == true, notify: vector["notify"] as? Bool == true,
                video: vector["video"] as? Bool == true, expiresAt: (vector["expiresAt"] as? NSNumber)?.int64Value ?? -1)
            #expect(read == expected, "\(vector["name"] ?? "")")
        }
    }

    @Test
    func lines() throws {
        for vector in try SharedVectors.array(file, key: "lines") {
            let events = try #require(vector["memberEvents"] as? [[String: Any]]).map {
                (userId: $0["userId"] as? String ?? "", ts: ($0["ts"] as? NSNumber)?.int64Value ?? 0)
            }
            let line = BipeCallRing.line(isDm: vector["isDm"] as? Bool == true, sender: try #require(vector["sender"] as? String),
                ringTs: try #require((vector["ringTs"] as? NSNumber)?.int64Value), active: vector["active"] as? Bool == true,
                declinedBy: try #require(vector["declinedBy"] as? [String]), memberEvents: events)
            let expected = BipeCallLine(outcome: BipeCallLine.Outcome(rawValue: vector["outcome"] as? String ?? "") ?? .ongoing,
                minutes: (vector["minutes"] as? NSNumber)?.intValue, joined: try #require(vector["joined"] as? [String]))
            #expect(line == expected, "\(vector["name"] ?? "")")
        }
    }
}
