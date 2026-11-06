// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
import MatrixRustSDK

struct MacMessage: Identifiable {
    let id: String
    let sender: String
    let body: String
    let timestamp: Date
    let isOwn: Bool
    let pending: Bool
    let failed: Bool
    let warning: ScamWarningKind?
}

/// The spike renders text and undecryptable placeholders. Rich media and other product features follow in #1018.
@MainActor
final class MacTimeline: ObservableObject {
    @Published private(set) var messages: [MacMessage] = []
    @Published private(set) var failed = false
    @Published private(set) var sending = false
    @Published private(set) var loadingHistory = false
    @Published var draft = ""
    private var timeline: Timeline?
    private var listener: TaskHandle?
    private var items: [TimelineItem] = []

    func run(room: Room) async {
        do {
            let isDm = (try? await room.roomInfo())?.isDm ?? false
            let configuration = TimelineConfiguration(focus: .live(hideThreadedEvents: !isDm), filter: .all,
                                                       internalIdPrefix: nil, dateDividerMode: .daily,
                                                       trackReadReceipts: .messageLikeEvents, reportUtds: false)
            let timeline = try await room.timelineWithConfiguration(configuration: configuration)
            self.timeline = timeline
            let stream = TimelineDiffStream()
            listener = await timeline.addListener(listener: stream)
            defer { listener?.cancel(); listener = nil; self.timeline = nil }
            for await updates in stream.updates {
                guard !Task.isCancelled else { return }
                for update in updates { items.apply(update.vectorDiff) }
                rebuild()
            }
        } catch { if !Task.isCancelled { failed = true } }
    }

    func loadHistory() async {
        guard let timeline, !loadingHistory else { return }
        loadingHistory = true
        defer { loadingHistory = false }
        do { _ = try await timeline.paginateBackwards(numEvents: 30) } catch { failed = true }
    }

    func send() async {
        guard let timeline, !sending, !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let text = draft
        sending = true
        failed = false
        defer { sending = false }
        do {
            _ = try await timeline.send(msg: messageEventContentFromMarkdown(md: text))
            if draft == text { draft = "" }
        } catch { failed = true }
    }

    private func rebuild() {
        messages = items.compactMap { item in
            guard let event = item.asEvent(), case .msgLike(let content) = event.content else { return nil }
            let body: String
            switch content.kind {
            case .message(let message): body = message.body
            case .unableToDecrypt: body = BipeStrings.webTimelineUndecryptable
            default: return nil
            }
            var displayName: String?
            if case .ready(let name, _, _, _, _) = event.senderProfile { displayName = name }
            let sender = BipeChatRowName.person(userID: event.sender, displayName: displayName, handle: nil)
            let pending: Bool
            let failed: Bool
            switch event.localSendState {
            case .notSentYet: pending = true; failed = false
            case .sendingFailed: pending = false; failed = true
            default: pending = false; failed = false
            }
            return MacMessage(id: item.uniqueId().id, sender: sender, body: body,
                              timestamp: Date(timeIntervalSince1970: Double(event.timestamp) / 1000),
                              isOwn: event.isOwn, pending: pending, failed: failed,
                              warning: event.isOwn ? nil : ScamDetector.warningKind(for: body, eventID: item.uniqueId().id))
        }
    }
}
