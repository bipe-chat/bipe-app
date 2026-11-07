// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import AppKit
import Foundation
import MatrixRustSDK
import XCTest
@testable import BipeMac

final class MacReactionTests: XCTestCase {
    @MainActor
    func testEveryQuickReactionHasDistinctVisibleNonTemplateMenuArtwork() throws {
        var renderings: Set<Data> = []
        for key in BipeReactions.quickKeys {
            let image = MacReactionArtwork.image(for: key)
            XCTAssertFalse(image.isTemplate, key)
            let cgImage = try XCTUnwrap(image.cgImage(forProposedRect: nil, context: nil, hints: nil))
            let bitmap = NSBitmapImageRep(cgImage: cgImage)
            var visiblePixels = 0
            for x in 0..<bitmap.pixelsWide {
                for y in 0..<bitmap.pixelsHigh {
                    if (bitmap.colorAt(x: x, y: y)?.alphaComponent ?? 0) > 0.1 { visiblePixels += 1 }
                }
            }
            XCTAssertGreaterThan(visiblePixels, 20, key)
            renderings.insert(try XCTUnwrap(bitmap.representation(using: .png, properties: [:])))
        }
        XCTAssertEqual(renderings.count, BipeReactions.quickKeys.count)
    }

    func testReactionSummaryUsesAccountIdentityAndNewestSenderFirst() throws {
        let own = ReactionSenderData(senderId: "@own:example.invalid", timestamp: 10)
        let other = ReactionSenderData(senderId: "@other:example.invalid", timestamp: 20)
        let reactions = [Reaction(key: "empty", senders: []), Reaction(key: "👍🏽", senders: [own, other])]
        let summary = try XCTUnwrap(MacReaction.summaries(reactions, ownUserID: own.senderId).first)
        XCTAssertEqual(summary.key, "👍🏽")
        XCTAssertEqual(summary.count, 2)
        XCTAssertTrue(summary.includesOwn)
        XCTAssertEqual(summary.senders.map(\.senderId), [other.senderId, own.senderId])
        XCTAssertFalse(try XCTUnwrap(MacReaction.summaries(reactions, ownUserID: "@third:example.invalid").first).includesOwn)
    }

    func testJoinedEmojiLabelsNeverSplitTheReactionKey() {
        let key = String(repeating: "👨‍👩‍👧‍👦", count: BipeReactions.displayKeyMaxGraphemes + 1)
        XCTAssertEqual(BipeReactions.keyText(key), String(repeating: "👨‍👩‍👧‍👦", count: BipeReactions.displayKeyMaxGraphemes) + "…")
    }

    func testPaletteStorageRejectsUnknownChoiceAndRestoresValidChoice() throws {
        let suite = "bipe.tests.appearance." + UUID().uuidString
        let store = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { store.removePersistentDomain(forName: suite) }
        XCTAssertEqual(AppTheme.palette(in: store), .standard)
        store.set("unknown", forKey: AppTheme.paletteKey)
        XCTAssertEqual(AppTheme.palette(in: store), .standard)
        for choice in BipePalette.allCases {
            store.set(choice.rawValue, forKey: AppTheme.paletteKey)
            XCTAssertEqual(AppTheme.palette(in: store), choice)
        }
    }

    @MainActor
    func testEveryPaletteHasDockArtworkWithoutChangingTheSignedBundle() {
        for choice in BipePalette.allCases {
            XCTAssertNotNil(NSImage(named: NSImage.Name("BipePalette-" + choice.rawValue)), choice.rawValue)
        }
    }
}
