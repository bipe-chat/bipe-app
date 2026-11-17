// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec chat-timeline jumpToDate: a calendar up to today. Picking a day jumps at once.
struct MacJumpToDateSheet: View {
    let pick: (Date) -> Void
    let cancel: () -> Void
    @State private var day = Date()

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.jumpToDateTitle).macFont(.headline)
            DatePicker(BipeStrings.jumpToDateTitle, selection: $day, in: ...Date(), displayedComponents: .date)
                .datePickerStyle(.graphical).labelsHidden()
                .onChange(of: day) { _, picked in pick(picked) }
            HStack {
                Spacer()
                Button(BipeStrings.appCancel, action: cancel).keyboardShortcut(.cancelAction)
            }
        }
        .padding(MacDesign.gap * 2)
        .frame(width: 340)
    }
}

/// Spec chat-timeline dayFocus (bipe#828): the picked day in a read-only view over the chat. The live chat keeps its
/// place, read receipts and composer; closing the day view returns to them.
struct MacDayView: View {
    let room: Room
    let day: Date
    @ObservedObject var media: MacMedia
    let isPrivateRoom: Bool
    let close: () -> Void
    @StateObject private var model: MacTimeline

    init(room: Room, focus: MacDayFocus, client: Client, drafts: MacDrafts, media: MacMedia, isPrivateRoom: Bool,
         close: @escaping () -> Void) {
        self.room = room
        day = focus.day
        self.media = media
        self.isPrivateRoom = isPrivateRoom
        self.close = close
        _model = StateObject(wrappedValue: MacTimeline(drafts: drafts, client: client, dayFocus: focus))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(daySeparatorText(day)).macFont(.headline)
                Spacer()
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
            }
            .padding()
            ScrollViewReader { scroll in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: MacDesign.gap) {
                        if !model.reachedStart, model.ready {
                            Button(model.loadingHistory ? BipeStrings.appTimelineLoadingHistory : BipeStrings.actionShowMore) {
                                Task { await model.loadHistory() }
                            }
                            .disabled(model.loadingHistory)
                        }
                        ForEach(model.rows) { row in
                            switch row {
                            case .message(let message):
                                MacMessageView(message: message, room: room, media: media, isPrivateRoom: isPrivateRoom,
                                               fromRequest: media.isFromRequest(room.id()),
                                               pauseSecondsLeft: { model.pauseSecondsLeft(message.id, now: $0) },
                                               menuItems: model.menuItems(for: message).filter { $0 == .copy || $0 == .copyLink },
                                               choose: { model.choose($0, message) },
                                               react: { _ in }, reactionPending: { _ in false },
                                               isHighlighted: model.highlightedRowID == message.id,
                                               openOriginal: { eventID in Task { await model.openOriginal(eventID: eventID) } },
                                               loadReply: { await model.loadReply(rowID: message.id) })
                                    .id(message.id).onAppear { model.startMoneyPause(message) }
                            case .day(let id, let date):
                                SystemLine(text: daySeparatorText(date)).id(id).accessibilityAddTraits(.isHeader)
                            case .line(let id, let text, let kind):
                                SystemLine(text: text, kind: kind).id(id)
                            case .readMarker(let id):
                                TimelineReadMarker().id(id)
                            }
                        }
                    }
                    .padding()
                }
                .onChange(of: model.scrollTarget) { _, target in
                    if let target {
                        scroll.scrollTo(target.rowID, anchor: .top)
                        model.scrolledToTarget(target)
                    }
                }
            }
        }
        .frame(minWidth: 520, minHeight: 560)
        .background(ChatBackgroundView())
        .task { await model.run(room: room) }
    }
}
