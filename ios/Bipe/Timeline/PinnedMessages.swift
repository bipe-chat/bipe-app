// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec message-pins (bipe#665): the pinned messages of a group, newest first.
@MainActor
final class PinnedMessagesModel: ObservableObject {
    /// Type Pin.
    struct Pin: Identifiable, Equatable {
        let eventId: String
        let senderName: String
        let text: String

        var id: String { eventId }
    }

    @Published private(set) var pins: [Pin] = []
    @Published private(set) var shownIndex = 0
    @Published var showsList = false

    private let session: AccountSession
    private let roomId: String
    private var handle: TaskHandle?
    private var items: [TimelineItem] = []
    private var started = false

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    var shown: Pin? { pins.indices.contains(shownIndex) ? pins[shownIndex] : pins.first }

    /// Listens to the pinned-events timeline until the chat closes. Groups only: the caller checks.
    func observe() async {
        guard !started, let room = try? session.client.getRoom(roomId: roomId) else { return }
        started = true
        let configuration = TimelineConfiguration(focus: .pinnedEvents,
                                                  filter: .all,
                                                  internalIdPrefix: "pinned",
                                                  dateDividerMode: .daily,
                                                  trackReadReceipts: .disabled,
                                                  reportUtds: false)
        guard let timeline = try? await room.timelineWithConfiguration(configuration: configuration) else { return }
        let stream = TimelineDiffStream()
        handle = await timeline.addListener(listener: stream)
        for await diffs in stream.updates {
            for diff in diffs {
                items.apply(diff.vectorDiff)
            }
            rebuild()
        }
    }

    /// Action tapBanner: the event to scroll to; the banner moves to the next older pin.
    func tapBanner() -> String? {
        guard let pin = shown else { return nil }
        shownIndex = pins.isEmpty ? 0 : (shownIndex + 1) % pins.count
        return pin.eventId
    }

    private func rebuild() {
        pins = items.reversed().compactMap { item in
            guard let event = item.asEvent(), case .eventId(let eventId) = event.eventOrTransactionId,
                  case .msgLike(let content) = event.content else { return nil }
            let text: String
            switch MediaContent(content.kind) {
            case .media(let media)?: text = media.caption?.nilIfBlank ?? media.kindLabel
            case .voice?: text = BipeStrings.appMediaVoice
            case nil:
                guard case .message(let message) = content.kind else { return nil }
                text = message.body
            }
            let sender = event.isOwn ? BipeStrings.appYou : event.senderProfile.displayName(or: event.sender)
            return Pin(eventId: eventId, senderName: sender, text: text.replacingOccurrences(of: "\n", with: " "))
        }
        if shownIndex >= pins.count { shownIndex = 0 }
    }
}

/// Spec message-pins: the banner above the timeline.
struct PinnedBanner: View {
    @ObservedObject var model: PinnedMessagesModel
    /// Scrolls the chat to an event (chat-timeline openOriginal).
    let jump: (String) -> Void

    var body: some View {
        if let pin = model.shown {
            HStack(spacing: 10) {
                Button {
                    if let eventId = model.tapBanner() { jump(eventId) }
                } label: {
                    HStack(spacing: 10) {
                        PhosphorIcon.chatText.image
                            .resizable()
                            .frame(width: 18, height: 18)
                            .foregroundStyle(BipeColor.accentInk)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(model.pins.count > 1
                                 ? BipeStrings.pinnedBannerCount(String(model.shownIndex + 1), String(model.pins.count))
                                 : BipeStrings.pinnedBannerTitle)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(BipeColor.accentInk)
                            HStack(spacing: 4) {
                                Text(pin.senderName)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(BipeColor.inkSecondary)
                                Text(pin.text)
                                    .font(.subheadline)
                                    .foregroundStyle(BipeColor.inkPrimary)
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
                        .font(.caption.weight(.semibold))
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(BipeColor.surfaceRaised)
            .sheet(isPresented: $model.showsList) {
                PinnedList(model: model) { eventId in
                    model.showsList = false
                    jump(eventId)
                }
            }
        }
    }
}

/// Spec message-pins openList.
private struct PinnedList: View {
    @ObservedObject var model: PinnedMessagesModel
    let open: (String) -> Void

    var body: some View {
        NavigationStack {
            List(model.pins) { pin in
                Button { open(pin.eventId) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(pin.senderName)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(BipeColor.inkSecondary)
                        Text(pin.text)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .lineLimit(3)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle(BipeStrings.pinnedListTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { model.showsList = false }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.messagePins.rawValue)
    }
}
