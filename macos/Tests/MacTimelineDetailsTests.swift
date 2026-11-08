// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import XCTest
@testable import BipeMac

/// The pinned SDK explicitly supports no-handle subclasses for local fixtures. Never passed back to Rust.
private final class ReplyFixture: InReplyToDetails, @unchecked Sendable {
    var embedded: EmbeddedEventDetails = .unavailable
    override func event() -> EmbeddedEventDetails { embedded }
    override func eventId() -> String { "$original" }
}

final class MacTimelineDetailsTests: XCTestCase {
    private func content(_ type: MessageType, body: String, edited: Bool = false) -> TimelineItemContent {
        .msgLike(content: MsgLikeContent(kind: .message(content: MessageContent(msgType: type, body: body,
            isEdited: edited, mentions: nil)), reactions: [], inReplyTo: nil, threadRoot: nil, threadSummary: nil))
    }

    private func resolve(_ fixture: ReplyFixture, tried: Bool = false, own: String = "@own:example.invalid") -> MessageReply {
        MessageReply.make(fixture, ownUserID: own, loadTried: tried, name: { _ in "Fallback name" },
                          preview: { MacReplyPreview.text($0, isOwn: $1) }, sourceText: MacReplyPreview.safetyText)
    }

    func testQuoteStatesNeverExposeUnavailableSourceText() {
        let fixture = ReplyFixture(noHandle: .init())
        XCTAssertEqual(resolve(fixture).state, .loading)
        XCTAssertEqual(resolve(fixture, tried: true).state, .unavailable)
        fixture.embedded = .pending
        XCTAssertEqual(resolve(fixture).state, .loading)
        fixture.embedded = .error(message: "synthetic offline error")
        let missing = resolve(fixture)
        XCTAssertEqual(missing.state, .unavailable)
        XCTAssertNil(missing.preview)
        XCTAssertTrue(missing.safetyText.isEmpty)
    }

    func testQuoteUsesRealSenderNameAndTheOwnAccountLabel() {
        let fixture = ReplyFixture(noHandle: .init())
        fixture.embedded = .ready(content: content(.text(content: TextMessageContent(body: "Original", formatted: nil)), body: "Original"),
            sender: "@person:example.invalid", senderProfile: .ready(displayName: "Bia", displayNameAmbiguous: false,
                avatarUrl: nil, status: nil, call: nil), timestamp: 1, eventOrTransactionId: .eventId(eventId: "$original"))
        let reply = resolve(fixture)
        XCTAssertEqual(reply.senderName, "Bia")
        XCTAssertEqual(reply.preview, "Original")
        XCTAssertEqual(resolve(fixture, own: "@person:example.invalid").senderName, BipeStrings.appYou)
    }

    func testQuotedBodyAndLinkDestinationsParticipateInScamChecks() {
        let quoted = content(.text(content: TextMessageContent(body: "urgente", formatted:
            FormattedBody(format: .html, body: "<a href='https://example.invalid/payment'>urgente</a>"))), body: "urgente")
        let fixture = ReplyFixture(noHandle: .init())
        fixture.embedded = .ready(content: quoted, sender: "@person:example.invalid", senderProfile: .unavailable,
                                  timestamp: 1, eventOrTransactionId: .eventId(eventId: "$original"))
        let reply = resolve(fixture)
        XCTAssertEqual(reply.preview, "urgente")
        XCTAssertTrue(reply.safetyText.contains("https://example.invalid/payment"))
        let harmlessReply = MacMessagePayload(body: "Please read the original")
        XCTAssertNil(harmlessReply.warning(including: nil, isOwn: false, eventID: "plain-reply"))
        XCTAssertNotNil(harmlessReply.warning(including: reply, isOwn: false, eventID: "quoted-reply"))
        XCTAssertNil(harmlessReply.warning(including: reply, isOwn: true, eventID: "own-reply"))
    }

    func testEditsInvalidateTheWarningCacheAndRedactionDropsAuthoredContent() throws {
        let before = try XCTUnwrap(MacMessagePayload(kind: .message(content: MessageContent(
            msgType: .text(content: TextMessageContent(body: "Hello", formatted: nil)), body: "Hello", isEdited: false, mentions: nil)), eventJson: nil))
        XCTAssertNil(before.warning(including: nil, isOwn: false, eventID: "edited-event"))
        let after = try XCTUnwrap(MacMessagePayload(kind: .message(content: MessageContent(
            msgType: .text(content: TextMessageContent(body: "urgente", formatted: nil)), body: "urgente", isEdited: true, mentions: nil)), eventJson: nil))
        XCTAssertTrue(after.isEdited)
        XCTAssertNotNil(after.warning(including: nil, isOwn: false, eventID: "edited-event"))
        let deleted = try XCTUnwrap(MacMessagePayload(kind: .redacted, eventJson: "private original"))
        XCTAssertTrue(deleted.isDeleted)
        XCTAssertFalse(deleted.isEdited)
        XCTAssertTrue(deleted.body.isEmpty)
        XCTAssertTrue(deleted.richText.text.isEmpty)
        XCTAssertNil(deleted.photo)
        XCTAssertNil(deleted.warning(including: nil, isOwn: false, eventID: "edited-event"))
    }

    func testRedactedQuoteUsesTheDeletedPlaceholder() {
        let deleted = TimelineItemContent.msgLike(content: MsgLikeContent(kind: .redacted, reactions: [],
            inReplyTo: nil, threadRoot: nil, threadSummary: nil))
        XCTAssertEqual(MacReplyPreview.text(deleted, isOwn: true), BipeStrings.appMessageDeletedOwn)
        XCTAssertEqual(MacReplyPreview.text(deleted, isOwn: false), BipeStrings.appMessageDeleted)
        XCTAssertTrue(MacReplyPreview.safetyText(deleted).isEmpty)
    }

    @MainActor
    func testOriginalLookupUsesLoadedRowsAndStopsAfterFindingAPaginatedTarget() async throws {
        var pages = 0
        let loaded = try await MacOriginalLookup.find(loaded: { "loaded-row" }, paginate: { pages += 1; return false })
        XCTAssertEqual(loaded, "loaded-row")
        XCTAssertEqual(pages, 0)
        let found = try await MacOriginalLookup.find(loaded: { pages == 2 ? "older-row" : nil }, paginate: {
            pages += 1
            return false
        })
        XCTAssertEqual(found, "older-row")
        XCTAssertEqual(pages, 2)
    }

    @MainActor
    func testMissingOriginalConsumesAtMostFivePagesAndHonoursTheHistoryEnd() async throws {
        var pages = 0
        let missing = try await MacOriginalLookup.find(loaded: { nil }, paginate: { pages += 1; return false })
        XCTAssertNil(missing)
        XCTAssertEqual(pages, 5)
        pages = 0
        let ended = try await MacOriginalLookup.find(loaded: { nil }, paginate: { pages += 1; return true })
        XCTAssertNil(ended)
        XCTAssertEqual(pages, 1)
    }

    @MainActor
    func testDeletedRowsHaveNoMessageActionsEvenWithStalePermissions() {
        var message = MacMessage(id: "row", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "", richText: BipeMessageText(body: ""), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: true, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$deleted", canReply: true, canReact: true, reactions: [])
        message.isDeleted = true
        message.canEdit = true
        message.canDelete = true
        let model = MacTimeline(drafts: MacDrafts())
        XCTAssertTrue(model.menuItems(for: message).isEmpty)
        XCTAssertFalse(message.isSent)
    }
}
