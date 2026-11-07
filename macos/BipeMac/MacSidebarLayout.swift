// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import SwiftUI

/// The same bounds constrain SwiftUI, AppKit dragging and a previously restored narrow split frame.
enum MacSidebarLayout {
    static let minimum: CGFloat = 300
    static let ideal: CGFloat = 360
    static let maximum: CGFloat = 440

    @MainActor static func repair(_ split: NSSplitView) {
        guard split.isVertical, split.subviews.count >= 2 else { return }
        if let controller = split.delegate as? NSSplitViewController, let item = controller.splitViewItems.first {
            if item.maximumThickness != maximum { item.maximumThickness = maximum }
            if item.minimumThickness != minimum { item.minimumThickness = minimum }
            if item.isCollapsed { return }
        }
        // A deliberately hidden sidebar stays hidden. No defaults or other window geometry are erased.
        let width = split.subviews[0].frame.width
        guard width > 1 else { return }
        if width < minimum - 0.5 { split.setPosition(minimum, ofDividerAt: 0) }
        else if width > maximum + 0.5 { split.setPosition(maximum, ofDividerAt: 0) }
    }
}

/// Apply only to this sidebar's nearest split view; never inspect or modify another app window.
struct MacSidebarSizing: NSViewRepresentable {
    func makeNSView(context: Context) -> Observer { Observer() }
    func updateNSView(_ view: Observer, context: Context) { view.schedule() }

    final class Observer: NSView {
        private weak var split: NSSplitView?
        private var scheduled = false

        override func hitTest(_ point: NSPoint) -> NSView? { nil }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if window == nil {
                if let split { NotificationCenter.default.removeObserver(self, name: NSSplitView.didResizeSubviewsNotification, object: split) }
                split = nil
            } else { schedule() }
        }
        override func viewDidMoveToSuperview() { super.viewDidMoveToSuperview(); schedule() }
        override func layout() { super.layout(); schedule() }

        func schedule() {
            guard !scheduled else { return }
            scheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.scheduled = false
                self.repair()
            }
        }

        private func repair() {
            guard window != nil else { return }
            var ancestor = superview
            while let view = ancestor {
                if let found = view as? NSSplitView, found.isVertical, found.subviews.count >= 2,
                   isDescendant(of: found.subviews[0]) {
                    if split !== found {
                        if let split { NotificationCenter.default.removeObserver(self, name: NSSplitView.didResizeSubviewsNotification, object: split) }
                        split = found
                        // Selector observers are zeroing on macOS 10.11+; no observer outlives this view.
                        NotificationCenter.default.addObserver(self, selector: #selector(resized), name: NSSplitView.didResizeSubviewsNotification, object: found)
                    }
                    MacSidebarLayout.repair(found)
                    return
                }
                ancestor = view.superview
            }
        }

        @objc private func resized(_ notification: Notification) { schedule() }
    }
}
