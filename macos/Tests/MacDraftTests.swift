// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import MatrixRustSDK
import XCTest
@testable import BipeMac

private actor DraftRoom: MacDraftRoom {
    private var draft: ComposerDraft?
    private var written: [String] = []
    nonisolated func id() -> String { "!same-room:example.invalid" }
    func loadComposerDraft(threadRoot: String?) async throws -> ComposerDraft? { draft }
    func saveComposerDraft(draft: ComposerDraft, threadRoot: String?) async throws {
        if draft.plainText == "refused" { throw MacVaultError.missingStore }
        if draft.plainText == "first" { try await Task.sleep(nanoseconds: 10_000_000) }
        self.draft = draft
        written.append(draft.plainText)
    }
    func clearComposerDraft(threadRoot: String?) async throws { draft = nil }
    func history() -> [String] { written }
}

final class MacDraftTests: XCTestCase {
    @MainActor
    func testReopeningWaitsForTheLatestOrderedWriteAndAccountsStaySeparate() async throws {
        let personal = MacDrafts()
        let company = MacDrafts()
        let personalRoom = DraftRoom()
        let companyRoom = DraftRoom()
        personal.save(room: personalRoom, text: "first", reply: nil, onFailure: {})
        personal.save(room: personalRoom, text: "personal latest", reply: "$reply", onFailure: {})
        company.save(room: companyRoom, text: "company only", reply: nil, onFailure: {})
        let restored = try await personal.load(room: personalRoom)
        let other = try await company.load(room: companyRoom)
        let history = await personalRoom.history()
        XCTAssertEqual(restored?.plainText, "personal latest")
        XCTAssertEqual(restored?.draftType, .reply(eventId: "$reply"))
        XCTAssertEqual(other?.plainText, "company only")
        XCTAssertEqual(history, ["first", "personal latest"])
        personal.save(room: personalRoom, text: "", reply: nil, onFailure: {})
        let cleared = try await personal.load(room: personalRoom)
        XCTAssertNil(cleared)
        let preserved = try await company.load(room: companyRoom)
        XCTAssertEqual(preserved?.plainText, "company only")
    }

    @MainActor
    func testAWriteFailureIsReportedAndDoesNotBlockTheNextEdit() async throws {
        let drafts = MacDrafts()
        let room = DraftRoom()
        var failures = 0
        drafts.save(room: room, text: "refused", reply: nil, onFailure: { failures += 1 })
        drafts.save(room: room, text: "retry", reply: nil, onFailure: { failures += 1 })
        let saved = try await drafts.load(room: room)
        XCTAssertEqual(failures, 1)
        XCTAssertEqual(saved?.plainText, "retry")
    }
}
