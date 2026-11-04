// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec message-forward selection (bipe#860): in selection mode a tap on a selectable row picks it.
/// Rows take no other input while the mode is on.
struct SelectableTimelineRow<Content: View>: View {
    @ObservedObject var model: ChatTimelineModel
    let row: TimelineRow
    @ViewBuilder let content: () -> Content

    var body: some View {
        if let selection = model.selection {
            if model.canSelect(row) {
                let isPicked = selection.contains(row.id)
                Button {
                    model.toggleSelection(rowId: row.id)
                } label: {
                    HStack(spacing: 8) {
                        SelectionMark(isPicked: isPicked)
                        content()
                            .allowsHitTesting(false)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isPicked ? .isSelected : [])
            } else {
                content()
                    .allowsHitTesting(false)
            }
        } else {
            content()
        }
    }
}

/// The mark of a selectable row: a check mark when picked, an empty ring when not (not only a colour).
private struct SelectionMark: View {
    let isPicked: Bool

    var body: some View {
        Group {
            if isPicked {
                PhosphorIcon.checkCircle.image
                    .resizable()
                    .foregroundStyle(BipeColor.accentInk)
            } else {
                Circle()
                    .stroke(BipeColor.lineStrong, lineWidth: 1.5)
                    .padding(2)
            }
        }
        .frame(width: 24, height: 24)
        .accessibilityHidden(true)
    }
}

/// Spec message-forward selection: the bar in place of the composer, with the count, Encaminhar and Apagar.
struct MessageSelectionBar: View {
    @ObservedObject var model: ChatTimelineModel

    var body: some View {
        HStack(spacing: 8) {
            Button(BipeStrings.appCancel) { model.cancelSelection() }
                .foregroundStyle(BipeColor.accentInk)
            Text(BipeStrings.selectCount(String(model.selection?.count ?? 0)))
                .font(.subheadline)
                .foregroundStyle(BipeColor.inkSecondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button(BipeStrings.forwardAction) { model.forwardSelection() }
                .buttonStyle(.bipeBordered)
                .disabled(!model.canForwardSelection)
            Button(BipeStrings.appMessageActionDelete, role: .destructive) { model.askDeleteSelection() }
                .buttonStyle(.bipeBordered)
                .disabled(!model.canDeleteSelection)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(BipeColor.surfaceRaised)
    }
}
