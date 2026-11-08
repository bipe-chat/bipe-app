// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// A favourite owns its right-click target. SwiftUI's List context menu targets the entire grid row.
struct MacFavouriteContextMenu: NSViewRepresentable {
    struct Item {
        let command: BipeChatMenuItem
        let isEnabled: Bool
        let action: @MainActor () -> Void
    }
    let items: [Item]
    let select: () -> Void

    func makeNSView(context: Context) -> Target { Target() }
    func updateNSView(_ view: Target, context: Context) {
        view.items = items
        view.select = select
    }

    final class Target: NSView {
        var items: [Item] = []
        var select: () -> Void = {}

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
            select()
            let menu = NSMenu()
            menu.autoenablesItems = false
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
