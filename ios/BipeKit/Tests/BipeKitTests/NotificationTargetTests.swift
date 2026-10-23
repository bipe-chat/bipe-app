// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing
import Foundation

struct NotificationTargetTests {
    @Test func sharedRoomKeepsTwoAccountsSeparate() throws {
        let first = try #require(BipeNotificationTarget(accountUserId: "@owner:example.invalid", roomId: "!shared:example.invalid"))
        let other = try #require(BipeNotificationTarget(accountUserId: "@other:example.invalid", roomId: first.roomId))
        #expect(first != other)
        #expect(first.identifier != other.identifier)
        #expect(first.identifier == "4ed58fedef0bf890aa305625182349782704c1457e912e921ea5be125678a36d")
        #expect(!first.identifier.contains("owner"))
        #expect(!first.identifier.contains("shared"))
        let opened = Set([first])
        #expect(!opened.contains(other))
        let delivered = [first, other].filter { $0 != first }
        #expect(delivered == [other])
    }

    @Test func ownerlessAndMalformedLegacyRoutesCannotBecomeActions() {
        for owner in [nil, "", "owner", "@:example.invalid", "@owner:", " @owner:example.invalid", "@owner:example.invalid\n"] as [String?] {
            #expect(BipeNotificationTarget(accountUserId: owner, roomId: "!shared:example.invalid") == nil)
        }
        for room in [nil, "", "shared", "!", "!shared room:example.invalid", "!shared\0:example.invalid"] as [String?] {
            #expect(BipeNotificationTarget(accountUserId: "@owner:example.invalid", roomId: room) == nil)
        }
    }

    @Test func routingCodesPersistWithoutFollowingTheCurrentAccountAndExpireAtSignOut() throws {
        let suite = "bipe.notification-test.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let routes = BipePushAccountRoutes(defaults: defaults)
        let first = routes.route(for: "@owner:example.invalid")
        let other = routes.route(for: "@other:example.invalid")
        #expect(first != other)
        #expect(!first.contains("owner"))
        #expect(routes.route(for: "@owner:example.invalid") == first)
        let recreated = BipePushAccountRoutes(defaults: defaults)
        #expect(recreated.account(for: first) == "@owner:example.invalid")
        #expect(recreated.account(for: other) == "@other:example.invalid")
        recreated.remove(userId: "@owner:example.invalid")
        #expect(recreated.account(for: first) == nil)
        #expect(recreated.account(for: other) == "@other:example.invalid")
        #expect(recreated.route(for: "@owner:example.invalid") != first)
        #expect(recreated.account(for: "legacy") == nil)
        defaults.set(["@owner:example.invalid": "ambiguous", "@other:example.invalid": "ambiguous"],
                     forKey: "bipe.push.accountRoutes.v1")
        #expect(recreated.account(for: "ambiguous") == nil)
    }

    @Test func accountCaseAndRoomIdentityArePreserved() throws {
        let first = try #require(BipeNotificationTarget(accountUserId: "@owner:example.invalid", roomId: "!shared:example.invalid"))
        #expect(first != BipeNotificationTarget(accountUserId: "@Owner:example.invalid", roomId: first.roomId))
        #expect(first != BipeNotificationTarget(accountUserId: first.accountUserId, roomId: "!different:example.invalid"))
        #expect(first == BipeNotificationTarget(accountUserId: first.accountUserId, roomId: first.roomId))
    }
}
