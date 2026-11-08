// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import QuartzCore
import SwiftUI
import XCTest
@testable import BipeMac

final class MacComposerTests: XCTestCase {
    @MainActor
    func testMenuSourceImagesAreRealSmallTemplateImages() {
        let assets = BipeComposerGlyph.allCases.map(\.rawValue) + ["image", "camera", "file", "sticker", "chart-bar"]
        for asset in assets {
            XCTAssertNotNil(NSImage(named: asset), asset)
            let image = MacComposerArtwork.image(asset: asset)
            XCTAssertEqual(image.size, NSSize(width: 22, height: 22), asset)
            XCTAssertTrue(image.isTemplate, asset)
        }
    }

    func testSharedAttachmentMenuKeepsTheSpecOrderAndPermissionGates() {
        XCTAssertEqual(AttachmentChoice.visible(canPoll: true, isNotes: false), [.photos, .camera, .file, .location, .sticker, .poll])
        XCTAssertEqual(AttachmentChoice.visible(canPoll: false, isNotes: false), [.photos, .camera, .file, .location, .sticker])
        XCTAssertEqual(AttachmentChoice.visible(canPoll: true, isNotes: true), [.photos, .camera, .file])
        XCTAssertEqual(AttachmentChoice.visible(canPoll: true, isNotes: false, supported: [.photos, .file]), [.photos, .file])
    }

    @MainActor
    func testEmptyAndReadyComposersStayCompactAtNarrowAndWideWidths() async throws {
        for width in [CGFloat(320), 720] {
            for text in ["", "A short message"] {
                let root = composer(text: text, editing: false).frame(width: width)
                let size = try await render(root, name: "composer-\(Int(width))-\(text.isEmpty ? "empty" : "ready")")
                XCTAssertGreaterThan(size.height, 0)
                XCTAssertLessThanOrEqual(size.height, 84, "An attachment asset must never enlarge the composer")
                XCTAssertEqual(size.width, width, accuracy: 1)
            }
        }
    }

    @MainActor
    func testEditingAndMultilineComposersKeepTheirHeightBounded() async throws {
        for editing in [false, true] {
            let root = composer(text: String(repeating: "A message that wraps to another line. ", count: 30), editing: editing).frame(width: 320)
            let size = try await render(root, name: "composer-multiline-\(editing ? "edit" : "message")")
            XCTAssertGreaterThan(size.height, 0)
            XCTAssertLessThanOrEqual(size.height, 200)
        }
    }

    @MainActor
    func testReplyAndEditPanelsUseTheSameCompactAppleChrome() async throws {
        for glyph in [BipeComposerGlyph.reply, .edit] {
            let root = BipeComposerPanel {
                BipeComposerContextBar(glyph: glyph, title: BipeStrings.appMessageEditing,
                                       preview: "A message preview", onClose: {})
            }.macFont(.body).frame(width: 320)
            let size = try await render(root, name: "composer-panel-" + glyph.rawValue)
            XCTAssertGreaterThan(size.height, 0)
            XCTAssertLessThanOrEqual(size.height, 120)
        }
    }

    func testReplyComposerUsesMediaLabelsWhenTheOriginalHasNoCaption() throws {
        let source = try MediaSource.fromUrl(url: "mxc://example.invalid/media")
        var message = MacMessage(id: "original", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "", richText: BipeMessageText(body: ""), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: false, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$original", canReply: true, canReact: true, reactions: [])
        message.file = MacFile(content: FileMessageContent(filename: "file.pdf", caption: nil, formattedCaption: nil, source: source, info: nil))
        XCTAssertEqual(message.composerPreview, BipeStrings.appMediaFile)
        message.file = nil
        message = MacMessage(id: "photo", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "", richText: BipeMessageText(body: ""),
            photo: MacPhoto(content: ImageMessageContent(filename: "photo.jpg", caption: nil, formattedCaption: nil, source: source, info: nil), description: nil),
            isEmote: false, timestamp: Date(timeIntervalSince1970: 1), isOwn: false, showsSender: false, pending: false,
            failed: false, warning: nil, eventID: "$photo", canReply: true, canReact: true, reactions: [])
        XCTAssertEqual(message.composerPreview, BipeStrings.appMediaImage)
    }

    @MainActor
    private func composer(text: String, editing: Bool) -> some View {
        MacComposer(isEditing: editing, canSend: !text.isEmpty, canAttach: true, preparing: false, choose: { _ in }, send: {}) {
            TextField(BipeStrings.appTimelineComposerPlaceholder, text: .constant(text), axis: .vertical)
                .lineLimit(1...5).textFieldStyle(.plain).padding(.vertical, 11)
        }.macFont(.body).environment(\.colorScheme, .light)
    }

    @MainActor
    func testEditPanelKeepsTheOriginalPreviewAndCancellationRestoresTheDraft() {
        let model = MacTimeline(drafts: MacDrafts())
        model.draft = "Unsent draft"
        model.choose(.edit, message())
        model.draft = "Changed text"
        XCTAssertEqual(model.editingPreview, "Original text")
        model.cancelEdit()
        XCTAssertNil(model.editingPreview)
        XCTAssertEqual(model.draft, "Unsent draft")
    }

    @MainActor
    func testStartingAReplyCancelsTheEditAndKeepsTheUnsentDraftAsOnIOS() {
        let model = MacTimeline(drafts: MacDrafts())
        model.draft = "Unsent draft"
        model.choose(.edit, message())
        model.draft = "Changed text"
        model.startReply(message())
        XCTAssertNil(model.editingID)
        XCTAssertNil(model.editingPreview)
        XCTAssertEqual(model.draft, "Unsent draft")
        XCTAssertEqual(model.replyID, "$original")
    }

    private func message() -> MacMessage {
        MacMessage(id: "original", sender: "Bia", senderID: "@person:example.invalid", senderNameAmbiguous: false,
            senderRole: nil, body: "Original text", richText: BipeMessageText(body: "Original text"), photo: nil, isEmote: false,
            timestamp: Date(timeIntervalSince1970: 1), isOwn: true, showsSender: false, pending: false, failed: false,
            warning: nil, eventID: "$original", canReply: true, canReact: true, reactions: [], canEdit: true)
    }

    @MainActor
    private func render<Content: View>(_ content: Content, name: String) async throws -> NSSize {
        let view = NSHostingView(rootView: content.background(BipeColor.surfaceBase))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 720, height: 200),
                              styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.appearance = NSAppearance(named: .aqua)
        window.contentView = view
        defer { window.close() }
        window.orderFront(nil)
        // SwiftUI renders after layout. Yield to its display work before asking AppKit for the pixels.
        try await Task.sleep(for: .milliseconds(150))
        view.layoutSubtreeIfNeeded()
        let size = view.fittingSize
        window.setContentSize(size)
        try await Task.sleep(for: .milliseconds(150))
        view.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
        var bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        var backend = "view"
        // Native text controls can draw through backing layers instead of NSView's bitmap cache.
        if colorCount(bitmap) <= 8, let layer = view.layer, let graphics = NSGraphicsContext(bitmapImageRep: bitmap) {
            let context = graphics.cgContext
            context.clear(CGRect(x: 0, y: 0, width: CGFloat(bitmap.pixelsWide), height: CGFloat(bitmap.pixelsHigh)))
            context.saveGState()
            context.scaleBy(x: CGFloat(bitmap.pixelsWide) / view.bounds.width,
                            y: CGFloat(bitmap.pixelsHigh) / view.bounds.height)
            layer.render(in: context)
            context.restoreGState()
            backend = "layer"
        }
        // AppKit's print renderer also supports native controls without a screen-recording permission.
        if colorCount(bitmap) <= 8,
           let image = NSImage(data: view.dataWithPDF(inside: view.bounds)),
           let tiff = image.tiffRepresentation, let printed = NSBitmapImageRep(data: tiff) {
            bitmap = printed
            backend = "print"
        }
        let colors = colorCount(bitmap)
        print("Composer render \(name): \(backend), \(bitmap.pixelsWide)x\(bitmap.pixelsHigh), \(colors) opaque colours")
        XCTAssertGreaterThan(colors, 8, "A blank or transparent snapshot is not visual evidence")
        let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
        attachment.name = name + "-" + backend
        attachment.lifetime = .keepAlways
        add(attachment)
        return size
    }

    private func colorCount(_ bitmap: NSBitmapImageRep) -> Int {
        var colors: Set<UInt32> = []
        for y in stride(from: 0, to: bitmap.pixelsHigh, by: 3) {
            for x in stride(from: 0, to: bitmap.pixelsWide, by: 3) {
                guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.deviceRGB), color.alphaComponent > 0.5 else { continue }
                let r = UInt32(max(0, min(1, color.redComponent)) * 255)
                let g = UInt32(max(0, min(1, color.greenComponent)) * 255)
                let b = UInt32(max(0, min(1, color.blueComponent)) * 255)
                colors.insert(r << 16 | g << 8 | b)
            }
        }
        return colors.count
    }
}
