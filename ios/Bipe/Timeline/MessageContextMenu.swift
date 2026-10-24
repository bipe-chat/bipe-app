// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Native long press and accessibility actions share current, account-owned row permissions.
struct MessageContextActions: View {
    @ObservedObject var model: ChatTimelineModel
    let rowId: String

    var body: some View {
        if let row = model.row(rowId) {
            if model.showsReactions(row) {
                Menu {
                    ForEach(ChatTimelineModel.quickReactions, id: \.self) { key in
                        Button(key) { model.react(rowId: rowId, key: key) }
                    }
                    Button(BipeStrings.appMessageActionMoreReactions) { model.moreReactions(rowId: rowId) }
                } label: {
                    Label(BipeStrings.appMessageActionMoreReactions, systemImage: "face.smiling")
                }
            }
            ForEach(model.actions(for: row)) { action in
                Button(role: action == .delete ? .destructive : nil) { model.choose(action, rowId: rowId) } label: {
                    Label(action.menuTitle, systemImage: action.menuSymbol)
                }
            }
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

    /// MZ requested SF Symbols in native context-menu options on 23 October 2026.
    var menuSymbol: String {
        switch self {
        case .reply: "arrowshape.turn.up.left"
        case .edit: "pencil"
        case .copy: "doc.on.doc"
        case .retry: "arrow.clockwise"
        case .delete: "trash"
        case .report: "flag"
        }
    }
}
