// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

struct DeviceFirstTests {
    @Test
    func recoveryLink() throws {
        let url = try #require(URL(string: "https://bipe.chat/recuperar/#abc_DEF-123"))
        #expect(BipeRecoveryLink.token(from: url) == "abc_DEF-123")
        #expect(try BipeRecoveryLink.token(from: #require(URL(string: "https://bipe.chat/recuperar/"))) == nil)
        #expect(try BipeRecoveryLink.token(from: #require(URL(string: "https://evil.example/recuperar/#abc"))) == nil)
        #expect(try BipeRecoveryLink.token(from: #require(URL(string: "https://bipe.chat/convite/#abc"))) == nil)
    }
    
    @Test
    func handleChangeLine() {
        let at = Date(timeIntervalSince1970: 0)
        #expect(BipeHandleChange(userId: "@a:x", old: nil, new: "ana", at: at).line(name: "Ana") == BipeStrings.handleSet("Ana", "ana"))
        #expect(BipeHandleChange(userId: "@a:x", old: "ana", new: "ana.s", at: at).line(name: "Ana") == BipeStrings.handleChanged("Ana", "ana.s", "ana"))
        #expect(BipeHandleChange(userId: "@a:x", old: "ana", new: nil, at: at).line(name: "Ana") == BipeStrings.handleRemoved("Ana", "ana"))
        #expect(BipeHandleChange(userId: "@a:x", old: nil, new: nil, at: at).line(name: "Ana") == nil)
    }
    
    @Test
    func handleChangePlacement() {
        let anchors = [nil, 10.0, 20.0, 30.0].map { BipeHandleChangePlacement.Anchor(timestamp: $0.map(Date.init(timeIntervalSince1970:))) }
        let change = { (user: String, time: Double) in BipeHandleChange(userId: user, old: nil, new: "h", at: Date(timeIntervalSince1970: time)) }
        let changes = [change("@a:x", 5), change("@a:x", 15), change("@b:x", 25), change("@c:x", 15), change("@a:x", 35)]
        
        let live = BipeHandleChangePlacement.placements(of: changes, in: anchors, members: ["@a:x", "@b:x"], joinedAt: ["@b:x": Date(timeIntervalSince1970: 26)], includesEnd: true)
        #expect(live.map(\.index) == [2, 4])
        #expect(live.map(\.change.at.timeIntervalSince1970) == [15, 35])
        
        let focused = BipeHandleChangePlacement.placements(of: changes, in: anchors, members: ["@a:x", "@b:x"], joinedAt: [:], includesEnd: false)
        #expect(focused.map(\.index) == [2, 3])
    }
    
    @Test
    func pinSanitize() {
        #expect(BipePIN.sanitize("12a34 5678 9") == "12345678")
    }
}
