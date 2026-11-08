// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// Each row or favourite owns its context target, rather than promoting a grid tile to its List row.
struct MacSidebarContextMenu: NSViewRepresentable {
    struct Item {
        let command: BipeChatMenuItem
        let isEnabled: Bool
        let action: @MainActor () -> Void
    }
    let items: [Item]
    let begin: () -> Void
    let end: () -> Void

    func makeNSView(context: Context) -> Target { Target() }
    func updateNSView(_ view: Target, context: Context) {
        view.items = items
        view.begin = begin
        view.end = end
    }

    final class Target: NSView, NSMenuDelegate {
        var items: [Item] = []
        var begin: () -> Void = {}
        var end: () -> Void = {}

        static func isContextClick(_ event: NSEvent?) -> Bool {
            guard let event else { return false }
            return event.type == .rightMouseDown || (event.type == .leftMouseDown && event.modifierFlags.contains(.control))
        }

        // Pass normal clicks and drags through to the SwiftUI button underneath.
        override func hitTest(_ point: NSPoint) -> NSView? {
            guard Self.isContextClick(NSApp.currentEvent), super.hitTest(point) != nil else { return nil }
            return self
        }

        override func menu(for event: NSEvent) -> NSMenu? {
            let menu = NSMenu()
            menu.autoenablesItems = false
            menu.delegate = self
            for entry in items {
                let target = Action(entry.action)
                let item = NSMenuItem(title: entry.command.title, action: #selector(Action.perform(_:)), keyEquivalent: "")
                item.target = target
                item.representedObject = target // NSMenuItem's action target is weak.
                item.image = NSImage(systemSymbolName: entry.command.symbol, accessibilityDescription: nil)
                item.isEnabled = entry.isEnabled
                menu.addItem(item)
            }
            return menu
        }

        func menuWillOpen(_ menu: NSMenu) { begin() }
        func menuDidClose(_ menu: NSMenu) { end() }

        override func rightMouseDown(with event: NSEvent) { showMenu(event) }
        override func mouseDown(with event: NSEvent) {
            if Self.isContextClick(event) { showMenu(event) }
            else { super.mouseDown(with: event) }
        }

        private func showMenu(_ event: NSEvent) {
            if let menu = menu(for: event) { NSMenu.popUpContextMenu(menu, with: event, for: self) }
        }
    }

    @MainActor private final class Action: NSObject {
        let handler: @MainActor () -> Void
        init(_ handler: @escaping @MainActor () -> Void) { self.handler = handler }
        @objc func perform(_ sender: NSMenuItem) { handler() }
    }
}
