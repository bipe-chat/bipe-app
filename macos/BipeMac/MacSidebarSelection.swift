// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import SwiftUI

/// One selection surface for rows and favourite tiles, from the current Bipe palette.
/// Selection describes the open conversation and persists when focus moves to the composer.
struct MacSidebarSelection: ViewModifier {
    let isSelected: Bool

    func body(content: Content) -> some View {
        content
            .foregroundStyle(isSelected ? BipeColor.inkOnAccent : BipeColor.inkPrimary)
            .background {
                if isSelected { RoundedRectangle(cornerRadius: 6).fill(BipeColor.accentFill) }
            }
            .background(MacSidebarSelectionPaint())
            .environment(\.backgroundProminence, .standard)
            .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Disable only AppKit's blue/grey paint. List still owns selection, keyboard navigation and scrolling.
/// The probe lives inside a row and touches only its own enclosing table and row view.
struct MacSidebarSelectionPaint: NSViewRepresentable {
    func makeNSView(context: Context) -> Probe { Probe() }
    func updateNSView(_ view: Probe, context: Context) { view.schedule() }

    final class Probe: NSView {
        private var scheduled = false

        override func hitTest(_ point: NSPoint) -> NSView? { nil }
        override func viewDidMoveToWindow() { super.viewDidMoveToWindow(); schedule() }
        override func viewDidMoveToSuperview() { super.viewDidMoveToSuperview(); schedule() }
        override func layout() { super.layout(); schedule() }

        func schedule() {
            guard !scheduled else { return }
            scheduled = true
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                self.scheduled = false
                self.suppressSystemPaint()
            }
        }

        private func suppressSystemPaint() {
            guard window != nil else { return }
            var ancestor = superview
            while let view = ancestor {
                if let row = view as? NSTableRowView, row.selectionHighlightStyle != .none {
                    row.selectionHighlightStyle = .none
                    row.needsDisplay = true
                }
                if let table = view as? NSTableView {
                    if table.selectionHighlightStyle != .none {
                        table.selectionHighlightStyle = .none
                        table.needsDisplay = true
                    }
                    return
                }
                ancestor = view.superview
            }
        }
    }
}
