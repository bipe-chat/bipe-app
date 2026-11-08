// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI
import XCTest
@testable import BipeMac

final class MacSidebarSelectionTests: XCTestCase {
    @MainActor
    func testRightClickTargetsOneFavouriteWithoutOpeningItOrSelectingTheGrid() async throws {
        let model = SidebarFixtureModel()
        let window = fixture(model: model, bothSelected: false, appearance: .darkAqua)
        defer { window.close() }
        try await settle(window)
        let content = try XCTUnwrap(window.contentView)
        let table = try XCTUnwrap(find(NSTableView.self, in: content))
        let targets = all(MacSidebarContextMenu.Target.self, in: content).sorted {
            $0.convert($0.bounds, to: content).minX < $1.convert($1.bounds, to: content).minX
        }
        XCTAssertEqual(targets.count, 2)
        let target = try XCTUnwrap(targets.last)
        XCTAssertLessThan(target.bounds.width, table.bounds.width / 2, "The menu target must be a tile, not the grid row")
        let event = try XCTUnwrap(NSEvent.mouseEvent(with: .rightMouseDown, location: .zero, modifierFlags: [],
            timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 1, clickCount: 1, pressure: 1))
        XCTAssertTrue(MacSidebarContextMenu.Target.isContextClick(event))
        let menu = try XCTUnwrap(target.menu(for: event))
        target.menuWillOpen(menu)
        try await settle(window)
        XCTAssertEqual(model.contextFavourite, "second-favourite")
        XCTAssertEqual(model.selected, "first", "Right-click must not open another chat or send its read receipt")
        XCTAssertEqual(menu.items.map(\.title), [BipeChatMenuItem.unfavourite.title, BipeChatMenuItem.notifications.title])
        XCTAssertTrue(menu.items[0].isEnabled)
        XCTAssertFalse(menu.items[1].isEnabled)
        XCTAssertFalse(menu.autoenablesItems)
        menu.performActionForItem(at: 0)
        XCTAssertEqual(model.lastMenuRoom, "second-favourite")
        XCTAssertEqual(model.lastMenuCommand, .unfavourite)
        target.menuDidClose(menu)
        XCTAssertNil(model.contextFavourite)
        XCTAssertEqual(table.selectionHighlightStyle, .none)
        let normal = try XCTUnwrap(NSEvent.mouseEvent(with: .leftMouseDown, location: .zero, modifierFlags: [],
            timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 2, clickCount: 1, pressure: 1))
        XCTAssertFalse(MacSidebarContextMenu.Target.isContextClick(normal))
        let control = try XCTUnwrap(NSEvent.mouseEvent(with: .leftMouseDown, location: .zero, modifierFlags: [.control],
            timestamp: 0, windowNumber: window.windowNumber, context: nil, eventNumber: 3, clickCount: 1, pressure: 1))
        XCTAssertTrue(MacSidebarContextMenu.Target.isContextClick(control))
    }

    @MainActor
    func testNativeListKeepsSelectionAndArrowKeysWithoutSystemPaint() async throws {
        let model = SidebarFixtureModel()
        let window = fixture(model: model, bothSelected: false, appearance: .aqua)
        defer { window.close() }
        try await settle(window)
        let table = try XCTUnwrap(find(NSTableView.self, in: try XCTUnwrap(window.contentView)))
        XCTAssertEqual(table.selectionHighlightStyle, .none)
        XCTAssertEqual(model.selected, "first")
        XCTAssertGreaterThanOrEqual(table.selectedRow, 0)
        XCTAssertTrue(window.makeFirstResponder(table))
        let down = try XCTUnwrap(NSEvent.keyEvent(with: .keyDown, location: .zero, modifierFlags: [],
            timestamp: 0, windowNumber: window.windowNumber, context: nil,
            characters: "\u{F701}", charactersIgnoringModifiers: "\u{F701}", isARepeat: false, keyCode: 125))
        table.keyDown(with: down)
        try await settle(window)
        XCTAssertEqual(model.selected, "second", "Native arrow-key selection must still open the next room")
        XCTAssertEqual(table.selectionHighlightStyle, .none)
        model.selected = "favourite"
        try await settle(window)
        XCTAssertEqual(model.selected, "favourite")
        XCTAssertEqual(table.selectionHighlightStyle, .none)
    }

    @MainActor
    func testFavouriteAndNativeRowPixelsFollowEveryPaletteAndKeepItAfterFocusMoves() async throws {
        let saved = BipeTheme.shared.palette
        defer { BipeTheme.shared.palette = saved }
        for appearance in [NSAppearance.Name.aqua, .darkAqua, .accessibilityHighContrastAqua, .accessibilityHighContrastDarkAqua] {
            let window = fixture(model: SidebarFixtureModel(), bothSelected: true, appearance: appearance)
            defer { window.close() }
            try await settle(window)
            let content = try XCTUnwrap(window.contentView)
            let table = try XCTUnwrap(find(NSTableView.self, in: content))
            let field = try XCTUnwrap(find(NSTextField.self, in: content, matching: { $0.isEditable }))
            for palette in BipePalette.allCases {
                // Change the running theme without recreating the window, as Settings does.
                BipeTheme.shared.palette = palette
                XCTAssertTrue(window.makeFirstResponder(table))
                try await settle(window)
                XCTAssertEqual(table.selectionHighlightStyle, .none)
                var fill: NSColor?
                var ink: NSColor?
                window.effectiveAppearance.performAsCurrentDrawingAppearance {
                    fill = NSColor(BipeColor.accentFill).usingColorSpace(.sRGB)
                    ink = NSColor(BipeColor.inkOnAccent).usingColorSpace(.sRGB)
                }
                let expectedFill = try XCTUnwrap(fill)
                let expectedInk = try XCTUnwrap(ink)
                for row in [0, 1] {
                    try assertPixels(table, row: row, fill: expectedFill, ink: expectedInk,
                                     name: "\(palette.rawValue)-\(appearance.rawValue)-\(row)", attach: palette == .jenipapo)
                }
                XCTAssertTrue(window.makeFirstResponder(field))
                try await settle(window)
                for row in [0, 1] {
                    try assertPixels(table, row: row, fill: expectedFill, ink: expectedInk,
                                     name: "\(palette.rawValue)-\(appearance.rawValue)-\(row)-composer-focus", attach: false)
                }
            }
        }
    }

    @MainActor
    private func fixture(model: SidebarFixtureModel, bothSelected: Bool, appearance: NSAppearance.Name) -> NSWindow {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 360, height: 400),
                              styleMask: [.titled], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.appearance = NSAppearance(named: appearance)
        window.contentView = NSHostingView(rootView: SidebarFixture(model: model, bothSelected: bothSelected))
        window.makeKeyAndOrderFront(nil)
        return window
    }

    @MainActor
    private func settle(_ window: NSWindow) async throws {
        try await Task.sleep(for: .milliseconds(150))
        window.contentView?.layoutSubtreeIfNeeded()
        window.displayIfNeeded()
    }

    @MainActor
    private func assertPixels(_ table: NSTableView, row: Int, fill: NSColor, ink: NSColor, name: String, attach: Bool) throws {
        let view = try XCTUnwrap(table.rowView(atRow: row, makeIfNecessary: true))
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        let fillPixels = count(bitmap, matching: fill)
        let inkPixels = count(bitmap, matching: ink)
        print("Sidebar render \(name): \(bitmap.pixelsWide)x\(bitmap.pixelsHigh), fill=\(fillPixels), ink=\(inkPixels)")
        XCTAssertGreaterThan(fillPixels, 100, "\(name): selection must use the Bipe accent, not system blue/grey or a blank capture")
        XCTAssertGreaterThan(inkPixels, 5, "\(name): selected labels must use ink.onAccent")
        if attach || fillPixels <= 100 || inkPixels <= 5 {
            let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
            let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
            attachment.name = "sidebar-" + name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }

    private func count(_ bitmap: NSBitmapImageRep, matching expected: NSColor) -> Int {
        var found = 0
        for y in stride(from: 0, to: bitmap.pixelsHigh, by: 2) {
            for x in stride(from: 0, to: bitmap.pixelsWide, by: 2) {
                guard let color = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.sRGB), color.alphaComponent > 0.95 else { continue }
                if abs(color.redComponent - expected.redComponent) < 0.025,
                   abs(color.greenComponent - expected.greenComponent) < 0.025,
                   abs(color.blueComponent - expected.blueComponent) < 0.025 { found += 1 }
            }
        }
        return found
    }

    @MainActor
    private func find<T: NSView>(_ type: T.Type, in view: NSView, matching: (T) -> Bool = { _ in true }) -> T? {
        if let found = view as? T, matching(found) { return found }
        for child in view.subviews { if let found = find(type, in: child, matching: matching) { return found } }
        return nil
    }

    @MainActor
    private func all<T: NSView>(_ type: T.Type, in view: NSView) -> [T] {
        (view as? T).map { [$0] } ?? view.subviews.flatMap { all(type, in: $0) }
    }
}

@MainActor
private final class SidebarFixtureModel: ObservableObject {
    @Published var selected: String? = "first"
    @Published var contextFavourite: String?
    var lastMenuRoom: String?
    var lastMenuCommand: BipeChatMenuItem?
}

private struct SidebarFixture: View {
    @ObservedObject var model: SidebarFixtureModel
    let bothSelected: Bool
    var body: some View {
        VStack(spacing: 0) {
            List(selection: $model.selected) {
                HStack {
                    favourite("favourite")
                    favourite("second-favourite")
                    Spacer()
                }.listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16)).selectionDisabled()
                entry("first", title: BipeStrings.requestsTitle)
                entry("second", title: BipeStrings.archivedTitle)
            }.listStyle(.sidebar).scrollContentBackground(.hidden)
            TextField(BipeStrings.appTimelineComposerPlaceholder, text: .constant(""))
                .textFieldStyle(.plain).padding(16)
        }.background(BipeColor.surfaceBase)
    }

    private func favourite(_ id: String) -> some View {
        Button { model.selected = id } label: {
            VStack(spacing: 6) {
                Circle().fill(BipeColor.accentWash).frame(width: 72, height: 72)
                Text(BipeStrings.chatFavourites).macFont(.subheadline)
            }.padding(8).frame(width: 120)
                .modifier(MacSidebarSelection(isSelected: (bothSelected && id == "favourite") || model.selected == id,
                                              isContextTarget: model.contextFavourite == id))
        }.buttonStyle(.plain)
            .overlay {
                MacSidebarContextMenu(items: [
                    .init(command: .unfavourite, isEnabled: true, action: {
                        model.lastMenuRoom = id; model.lastMenuCommand = .unfavourite
                    }),
                    .init(command: .notifications, isEnabled: false, action: {})
                ], begin: { model.contextFavourite = id }, end: { model.contextFavourite = nil }).accessibilityHidden(true)
            }
    }

    private func entry(_ id: String, title: String) -> some View {
        Text(title).macFont(.headline).frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .modifier(MacSidebarSelection(isSelected: model.selected == id)).tag(id)
            .listRowInsets(EdgeInsets())
    }
}
