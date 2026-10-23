// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

struct RoomSubscriptionsTests {
    private actor Recorder {
        var calls: [[String]] = []
        var failsOnce: Bool
        init(failsOnce: Bool = false) { self.failsOnce = failsOnce }
        func apply(_ ids: [String]) throws {
            calls.append(ids)
            if failsOnce { failsOnce = false; throw Failure.synthetic }
        }
        func values() -> [[String]] { calls }
    }
    private enum Failure: Error { case synthetic }

    @Test func ownersShareOneUnionAndReleaseIndependently() async {
        let recorder = Recorder()
        let subscriptions = BipeRoomSubscriptions { try await recorder.apply($0) }
        await subscriptions.update(owner: "chats", roomIDs: ["!a:example.test", "!a:example.test"])
        await subscriptions.update(owner: "unread", roomIDs: ["!a:example.test", "!b:example.test"])
        await subscriptions.remove(owner: "chats") // Same union, not another SDK call.
        await subscriptions.remove(owner: "unread")
        #expect(await recorder.values() == [["!a:example.test"], ["!a:example.test", "!b:example.test"], []])
    }

    @Test func failureRetriesAndClosedAccountCannotRevive() async {
        let recorder = Recorder(failsOnce: true)
        let subscriptions = BipeRoomSubscriptions { try await recorder.apply($0) }
        await subscriptions.update(owner: "chats", roomIDs: ["!a:example.test"])
        await subscriptions.update(owner: "chats", roomIDs: ["!a:example.test"])
        await subscriptions.close()
        await subscriptions.update(owner: "late-view", roomIDs: ["!b:example.test"])
        #expect(await recorder.values() == [["!a:example.test"], ["!a:example.test"], []])
        let second = Recorder()
        let otherAccount = BipeRoomSubscriptions { try await second.apply($0) }
        await otherAccount.update(owner: "chats", roomIDs: ["!c:example.test"])
        #expect(await second.values() == [["!c:example.test"]])
    }

    private actor PausedApply {
        var calls: [[String]] = []
        var started = false
        var onStart: CheckedContinuation<Void, Never>?
        var release: CheckedContinuation<Void, Never>?
        func apply(_ ids: [String]) async {
            calls.append(ids)
            if calls.count == 1 {
                started = true
                onStart?.resume(); onStart = nil
                await withCheckedContinuation { release = $0 }
            }
        }
        func waitForStart() async {
            if !started { await withCheckedContinuation { onStart = $0 } }
        }
        func finish() { release?.resume(); release = nil }
        func values() -> [[String]] { calls }
    }

    @Test func ownerChangesDuringSDKCallDrainTheNewestUnion() async {
        let recorder = PausedApply()
        let subscriptions = BipeRoomSubscriptions { await recorder.apply($0) }
        let first = Task { await subscriptions.update(owner: "chats", roomIDs: ["!a:example.test"]) }
        await recorder.waitForStart()
        await subscriptions.update(owner: "groups", roomIDs: ["!b:example.test"])
        await subscriptions.remove(owner: "chats")
        await recorder.finish()
        await first.value
        #expect(await recorder.values() == [["!a:example.test"], ["!b:example.test"]])
    }

    @Test func SDKNameSurvivesMissingHeroMetadata() {
        #expect(BipeChatRowName.resolve(roomID: "!room:example.test", sdkName: " Example Support ", peerName: nil, peerID: nil, unnamed: "Unnamed") == "Example Support")
        #expect(BipeChatRowName.resolve(roomID: "!room:example.test", sdkName: " ", peerName: "Example Person", peerID: "@person:example.test", unnamed: "Unnamed") == "Example Person")
        #expect(BipeChatRowName.resolve(roomID: "!room:example.test", sdkName: "!room:example.test", peerName: "@person:example.test", peerID: "@person:example.test", unnamed: "Unnamed") == "person")
    }
}
