// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec message-pins (bipe#665) on the Mac: the pinned messages of a group, newest first. The same pinned-events
/// timeline as iOS PinnedMessagesModel; the rows use the Mac message payload.
@MainActor
final class MacPinsModel: ObservableObject {
    /// Type Pin.
    struct Pin: Identifiable, Equatable {
        let eventID: String
        let senderName: String
        let text: String
        var id: String { eventID }
    }

    @Published private(set) var pins: [Pin] = []
    @Published private(set) var shownIndex = 0
    @Published var showsList = false
    private var items: [TimelineItem] = []

    var shown: Pin? { pins.indices.contains(shownIndex) ? pins[shownIndex] : pins.first }

    /// Listens to the pinned-events timeline until the chat closes.
    func observe(room: Room) async {
        let configuration = TimelineConfiguration(focus: .pinnedEvents, filter: .all, internalIdPrefix: "pinned",
                                                  dateDividerMode: .daily, trackReadReceipts: .disabled, reportUtds: false)
        guard let timeline = try? await room.timelineWithConfiguration(configuration: configuration) else { return }
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        let ownUserID = room.ownUserId()
        for await diffs in stream.updates {
            guard !Task.isCancelled else { return }
            for diff in diffs { items.apply(diff.vectorDiff) }
            pins = Self.pins(items, ownUserID: ownUserID)
            if shownIndex >= pins.count { shownIndex = 0 }
        }
    }

    /// Action tapBanner: the event to scroll to; the banner moves to the next older pin, and from the oldest to the newest.
    func tapBanner() -> String? {
        guard let pin = shown else { return nil }
        shownIndex = pins.isEmpty ? 0 : (shownIndex + 1) % pins.count
        return pin.eventID
    }

    /// Newest pin first. Deleted and undecryptable events, and events the Mac cannot show, are left out.
    nonisolated static func pins(_ items: [TimelineItem], ownUserID: String) -> [Pin] {
        items.reversed().compactMap { item in
            guard let event = item.asEvent(), case .eventId(let eventID) = event.eventOrTransactionId,
                  case .msgLike(let content) = event.content,
                  let payload = MacMessagePayload(kind: content.kind, eventJson: nil),
                  !payload.isDeleted, !payload.isUndecryptable else { return nil }
            let sender = event.sender == ownUserID ? BipeStrings.appYou : MacRoomSummary.personName(event.senderProfile, userID: event.sender)
            return Pin(eventID: eventID, senderName: sender, text: text(payload, kind: content.kind))
        }
    }

    /// The body, the media caption or the media kind label, on one line.
    nonisolated static func text(_ payload: MacMessagePayload, kind: MsgLikeKind) -> String {
        let caption = payload.body.trimmingCharacters(in: .whitespacesAndNewlines)
        let label: String?
        if case .message(let message) = kind, case .audio = message.msgType { return BipeStrings.appMediaVoice }
        if payload.photo != nil { label = BipeStrings.appMediaImage }
        else if payload.video != nil { label = BipeStrings.appMediaVideo }
        else if payload.file != nil { label = BipeStrings.appMediaFile }
        else { label = nil }
        let text = label.map { caption.isEmpty ? $0 : caption } ?? payload.body
        return text.replacingOccurrences(of: "\n", with: " ")
    }
}

/// Spec message-pins: the banner above the timeline of a group.
struct MacPinnedBanner: View {
    @ObservedObject var model: MacPinsModel
    /// Scrolls the chat to an event (chat-timeline openOriginal).
    let jump: (String) -> Void

    var body: some View {
        if let pin = model.shown {
            HStack(spacing: 10) {
                Button {
                    if let eventID = model.tapBanner() { jump(eventID) }
                } label: {
                    HStack(spacing: 10) {
                        Image(decorative: "chat-text").renderingMode(.template).resizable()
                            .frame(width: 18, height: 18).foregroundStyle(BipeColor.accentInk)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(model.pins.count > 1
                                 ? BipeStrings.pinnedBannerCount(String(model.shownIndex + 1), String(model.pins.count))
                                 : BipeStrings.pinnedBannerTitle)
                                .macFont(.caption).fontWeight(.semibold).foregroundStyle(BipeColor.accentInk)
                            HStack(spacing: 4) {
                                Text(pin.senderName).macFont(.subheadline).fontWeight(.medium).foregroundStyle(BipeColor.inkSecondary)
                                Text(pin.text).macFont(.subheadline).foregroundStyle(BipeColor.inkPrimary)
                            }
                            .lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                if model.pins.count > 1 {
                    Button(BipeStrings.pinnedViewAll) { model.showsList = true }
                        .buttonStyle(.link).macFont(.caption)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(BipeColor.surfaceRaised)
            .sheet(isPresented: $model.showsList) {
                MacPinnedList(model: model) { eventID in
                    model.showsList = false
                    jump(eventID)
                }
            }
        }
    }
}

/// Spec message-pins openList: every pin; a click closes the sheet and scrolls to the message.
private struct MacPinnedList: View {
    @ObservedObject var model: MacPinsModel
    let open: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(BipeStrings.pinnedListTitle).macFont(.headline).padding()
            List(model.pins) { pin in
                Button { open(pin.eventID) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(pin.senderName).macFont(.caption).fontWeight(.semibold).foregroundStyle(BipeColor.inkSecondary)
                        Text(pin.text).macFont(.body).foregroundStyle(BipeColor.inkPrimary).lineLimit(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            HStack {
                Spacer()
                Button(BipeStrings.appClose) { model.showsList = false }.keyboardShortcut(.cancelAction)
            }
            .padding()
        }
        .frame(width: 420, height: 420)
        .accessibilityIdentifier(BipeScreen.messagePins.rawValue)
    }
}
