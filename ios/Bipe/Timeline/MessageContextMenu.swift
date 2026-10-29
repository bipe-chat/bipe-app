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
                    Label(model.menuTitle(action, rowId: rowId), systemImage: action.menuSymbol)
                }
                .disabled(action == .copy && model.pauseSecondsLeft(rowId: rowId) > 0)
            }
        }
    }
}

extension MessageAction {
    var menuTitle: String {
        switch self {
        case .reply: BipeStrings.appMessageActionReply
        case .replyInThread: BipeStrings.threadReplyAction
        case .edit: BipeStrings.appMessageActionEdit
        case .removeCaption: BipeStrings.captionRemove
        case .copy: BipeStrings.appCopy
        case .pin: BipeStrings.pinAction
        case .unpin: BipeStrings.unpinAction
        case .forward: BipeStrings.forwardAction
        case .saveSticker: BipeStrings.actionSaveSticker
        case .keepAudio: BipeStrings.voiceKeep
        case .unkeepAudio: BipeStrings.voiceUnkeep
        case .retry: BipeStrings.appRetry
        case .delete: BipeStrings.appMessageActionDelete
        case .report: BipeStrings.appMessageActionReport
        }
    }

    var menuIcon: PhosphorIcon {
        switch self {
        case .reply: .arrowBendUpLeft
        case .replyInThread: .chatText
        case .edit: .pencilSimple
        case .removeCaption: .x
        case .copy: .copy
        case .pin, .unpin: .chatText
        case .forward: .paperPlaneRight
        case .saveSticker: .sticker
        case .keepAudio: .downloadSimple
        case .unkeepAudio: .x
        case .retry: .arrowClockwise
        case .delete: .trash
        case .report: .flag
        }
    }

    /// MZ requested SF Symbols in native context-menu options on 23 October 2026.
    var menuSymbol: String {
        switch self {
        case .reply: "arrowshape.turn.up.left"
        case .replyInThread: "bubble.left.and.text.bubble.right"
        case .edit: "pencil"
        case .removeCaption: "text.badge.minus"
        case .copy: "doc.on.doc"
        case .pin: "pin"
        case .unpin: "pin.slash"
        case .forward: "arrowshape.turn.up.right"
        case .saveSticker: "square.and.arrow.down"
        case .keepAudio: "bookmark"
        case .unkeepAudio: "bookmark.slash"
        case .retry: "arrow.clockwise"
        case .delete: "trash"
        case .report: "flag"
        }
    }
}
