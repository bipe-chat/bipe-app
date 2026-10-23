// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Native long press and visible overflow share current, account-owned row permissions.
struct MessageContextActions: View {
    @ObservedObject var model: ChatTimelineModel
    let rowId: String

    var body: some View {
        if let row = model.row(rowId) {
            if model.showsReactions(row) {
                Menu(BipeStrings.appMessageActionMoreReactions) {
                    ForEach(ChatTimelineModel.quickReactions, id: \.self) { key in
                        Button(key) { model.react(rowId: rowId, key: key) }
                    }
                    Button(BipeStrings.appMessageActionMoreReactions) { model.moreReactions(rowId: rowId) }
                }
            }
            ForEach(model.actions(for: row)) { action in
                Button(role: action == .delete ? .destructive : nil) { model.choose(action, rowId: rowId) } label: {
                    Label { Text(action.menuTitle) } icon: { action.menuIcon.image }
                }
            }
        }
    }
}

struct MessageOverflowMenu: View {
    @ObservedObject var model: ChatTimelineModel
    let rowId: String

    var body: some View {
        if let row = model.row(rowId), model.showsReactions(row) || !model.actions(for: row).isEmpty {
            Menu { MessageContextActions(model: model, rowId: rowId) } label: {
                PhosphorIcon.dotsThree.image.resizable().frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.glyphSecondary).frame(width: 44, height: 44)
            }
            .accessibilityLabel(BipeStrings.messageActions)
        }
    }
}

extension MessageAction {
    var menuTitle: String {
        switch self {
        case .reply: BipeStrings.appMessageActionReply
        case .edit: BipeStrings.appMessageActionEdit
        case .copy: BipeStrings.appCopy
        case .retry: BipeStrings.appRetry
        case .delete: BipeStrings.appMessageActionDelete
        case .report: BipeStrings.appMessageActionReport
        }
    }

    var menuIcon: PhosphorIcon {
        switch self {
        case .reply: .arrowBendUpLeft
        case .edit: .pencilSimple
        case .copy: .copy
        case .retry: .arrowClockwise
        case .delete: .trash
        case .report: .flag
        }
    }
}
