// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import XCTest
@testable import BipeMac

final class MacSidebarTests: XCTestCase {
    @MainActor
    func testRestoredNarrowSidebarIsRepairedWithoutClearingAnyDefaults() {
        let split = NSSplitView(frame: NSRect(x: 0, y: 0, width: 1040, height: 600))
        split.isVertical = true
        split.addSubview(NSView(frame: NSRect(x: 0, y: 0, width: 130, height: 600)))
        split.addSubview(NSView(frame: NSRect(x: 131, y: 0, width: 909, height: 600)))
        split.setPosition(130, ofDividerAt: 0)
        MacSidebarLayout.repair(split)
        XCTAssertGreaterThanOrEqual(split.subviews[0].frame.width, MacSidebarLayout.minimum - 0.5)
        split.setPosition(360, ofDividerAt: 0)
        MacSidebarLayout.repair(split)
        XCTAssertEqual(split.subviews[0].frame.width, 360, accuracy: 0.5)
    }

    @MainActor
    func testNativeColumnRejectsDraggingBelowMinimumAndKeepsCollapseAvailable() {
        let sidebar = NSViewController()
        sidebar.view = NSView(frame: NSRect(x: 0, y: 0, width: 130, height: 600))
        let detail = NSViewController()
        detail.view = NSView(frame: NSRect(x: 0, y: 0, width: 680, height: 600))
        let controller = NSSplitViewController()
        controller.splitView.isVertical = true
        let item = NSSplitViewItem(sidebarWithViewController: sidebar)
        controller.addSplitViewItem(item)
        controller.addSplitViewItem(NSSplitViewItem(viewController: detail))
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1040, height: 600),
                              styleMask: [.titled, .resizable], backing: .buffered, defer: false)
        window.isReleasedWhenClosed = false
        window.contentViewController = controller
        defer { window.close() }
        controller.view.layoutSubtreeIfNeeded()
        MacSidebarLayout.repair(controller.splitView)
        controller.splitView.setPosition(130, ofDividerAt: 0)
        controller.view.layoutSubtreeIfNeeded()
        XCTAssertGreaterThanOrEqual(controller.splitView.subviews[0].frame.width, MacSidebarLayout.minimum - 0.5)
        item.isCollapsed = true
        MacSidebarLayout.repair(controller.splitView)
        XCTAssertTrue(item.isCollapsed)
    }
}
