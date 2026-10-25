// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Foundation
import Testing

/// Linked Apple or Google account for recovery (ADR 0096): the link-done redirect and the provider list.
struct LinkedRecoveryTests {
    private let scheme = "chat.bipe.app.next"

    @Test
    func linkDoneCarriesTheHandoffOnlyForThisApp() throws {
        let done = try #require(URL(string: "chat.bipe.app.next:/link-done?flow=abc_123#hand-OFF_9"))
        #expect(BipeLinkDone(url: done, scheme: scheme) == .done(flowId: "abc_123", handoff: "hand-OFF_9"))
        let cancelled = try #require(URL(string: "chat.bipe.app.next:/link-done?flow=abc&error=cancelled"))
        #expect(BipeLinkDone(url: cancelled, scheme: scheme) == .cancelled(flowId: "abc"))
        let failed = try #require(URL(string: "chat.bipe.app.next:/link-done?flow=abc&error=invalid"))
        #expect(BipeLinkDone(url: failed, scheme: scheme) == .failed(flowId: "abc"))
        let noHandoff = try #require(URL(string: "chat.bipe.app.next:/link-done?flow=abc"))
        #expect(BipeLinkDone(url: noHandoff, scheme: scheme) == .failed(flowId: "abc"))
        let otherApp = try #require(URL(string: "chat.bipe.app:/link-done?flow=abc#x"))
        #expect(BipeLinkDone(url: otherApp, scheme: scheme) == nil)
        let otherPath = try #require(URL(string: "chat.bipe.app.next:/other?flow=abc#x"))
        #expect(BipeLinkDone(url: otherPath, scheme: scheme) == nil)
        let badFlow = try #require(URL(string: "chat.bipe.app.next:/link-done?flow=a%2Fb#x"))
        #expect(BipeLinkDone(url: badFlow, scheme: scheme) == nil)
    }

    @Test
    func iosUsesAppleNativelyAndGoogleOnTheWeb() throws {
        let json = #"{"providers":[{"provider":"apple","native":false,"web":true},{"provider":"google","native":false,"web":true}]}"#
        let providers = try JSONDecoder.snakeCase.decode(BipeLinkProviders.self, from: Data(json.utf8))
        #expect(providers.usableOnIOS == [.google])
        let both = #"{"providers":[{"provider":"apple","native":true,"web":false},{"provider":"google","native":false,"web":true}]}"#
        #expect(try JSONDecoder.snakeCase.decode(BipeLinkProviders.self, from: Data(both.utf8)).usableOnIOS == [.apple, .google])
    }

    @Test
    func linkedRecoveryAlertsCanBeCancelled() {
        let recovery = BipeRecovery(id: "r", method: .google, purpose: .access, state: .waiting, createdAt: .now, readyAt: .now, endedAt: nil)
        #expect(recovery.alertMessage == BipeStrings.recoveryLinkedGoogleAlert)
        #expect(recovery.canBeCancelled)
    }
}

private extension JSONDecoder {
    static var snakeCase: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }
}
