// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import XCTest
@testable import BipeMac

final class MacListTests: XCTestCase {
    func testDmCannotSubstituteRoomPhotoForTheOtherPersonsPhoto() {
        let own = MacAvatarPerson(id: "@self:example.invalid", name: "Self", avatarURL: "mxc://example.invalid/own")
        let peer = MacAvatarPerson(id: "@peer:example.invalid", name: "Peer", avatarURL: nil)
        let face = MacAvatarDescriptor.room(isDm: true, photo: "mxc://example.invalid/spoof", heroes: [own, peer], ownUserID: own.id)
        XCTAssertEqual(face.userID, peer.id)
        XCTAssertNil(face.url)
        XCTAssertTrue(face.members.isEmpty)
    }

    func testGroupPhotoWinsOtherwiseMembersExcludeSelfAndStopAtThree() {
        let people = (0..<5).map { MacAvatarPerson(id: "@person\($0):example.invalid", name: "Person \($0)", avatarURL: nil) }
        let cluster = MacAvatarDescriptor.room(isDm: false, photo: "", heroes: people, ownUserID: people[0].id)
        XCTAssertNil(cluster.userID)
        XCTAssertEqual(cluster.members.map(\.id), Array(people.dropFirst().prefix(3)).map(\.id))
        let photo = MacAvatarDescriptor.room(isDm: false, photo: "mxc://example.invalid/group", heroes: people, ownUserID: people[0].id)
        XCTAssertEqual(photo.url, "mxc://example.invalid/group")
        XCTAssertTrue(photo.members.isEmpty)
        let one = MacAvatarDescriptor.room(isDm: false, photo: nil, heroes: Array(people.prefix(2)), ownUserID: people[0].id)
        XCTAssertEqual(one.members.count, 1)
        let empty = MacAvatarDescriptor.room(isDm: false, photo: nil, heroes: [people[0]], ownUserID: people[0].id)
        XCTAssertTrue(empty.members.isEmpty)
    }

    func testBadgesCountUnreadDmsAndGroupAttentionRatherThanAllGroupMessages() {
        let counts = MacTabAttention([
            .init(isDm: true, unread: 100, markedUnread: false, mentions: 0),
            .init(isDm: true, unread: 0, markedUnread: true, mentions: 0),
            .init(isDm: true, unread: 0, markedUnread: false, mentions: 0),
            .init(isDm: false, unread: 400, markedUnread: false, mentions: 0),
            .init(isDm: false, unread: 20, markedUnread: false, mentions: 3),
        ])
        XCTAssertEqual(counts.chats, 2)
        XCTAssertEqual(counts.groups, 3)
    }

    func testMissingLatestEventPreservesKnownDateAndPreviewWithoutInventingNow() {
        let known = BipeRowActivity.Row(timestampMs: 1234, previewText: "cached message", latestIncomingMs: 1234)
        XCTAssertEqual(MacRoomSummary.activity(.none, isDm: false, known: known), known)
        let empty = MacRoomSummary.activity(.none, isDm: false, known: nil)
        XCTAssertNil(empty.timestampMs)
        XCTAssertNil(empty.previewText)
    }
}
