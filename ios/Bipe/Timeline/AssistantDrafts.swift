// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import Phosphor
import SwiftUI

/// Spec drafts (bipe-chat/bipe#866, ADR 0210): the drafts that the user's own assistant wrote in the account's private
/// drafts room. The app reads the room named by the global account data chat.bipe.drafts_room and never creates it.
/// Read at start and when the app comes to the front; the drafts then follow the room timeline live.
@MainActor
final class AssistantDraftsStore: ObservableObject {
    static let shared = AssistantDraftsStore()

    /// Type AssistantDraft of spec drafts.
    struct Draft: Identifiable, Equatable {
        let eventId: String
        let roomId: String
        let roomName: String
        let body: String
        let replyTo: String?
        let threadRoot: String?
        let client: String
        let createdAt: Date

        var id: String { eventId }
    }

    /// State drafts: newest first.
    @Published private(set) var drafts: [Draft] = []
    /// State sending: the draft whose send runs.
    @Published private(set) var sendingEventId: String?
    /// State failed: the draft whose last send failed.
    @Published private(set) var failedEventId: String?
    /// bipe_app_drafts_nothing_sent shows until the first send, edit or discard on this device.
    @Published private(set) var showsNothingSent: Bool
    /// Goes up when a drafts room ID becomes known, so the room lists hide it.
    @Published private(set) var revision: UInt64 = 0
    /// The account whose drafts are shown.
    private(set) var userId: String?

    private static let roomsKey = "bipe.drafts.rooms"
    private static let nothingSentKey = "bipe.drafts.nothingSentShown"
    /// User ID to drafts room ID, kept so the lists hide the room before the account data is read again.
    private var roomIds: [String: String]
    /// Spec drafts openDraft: the draft the banner shows per target chat, instead of the newest.
    private var focused: [String: String] = [:]
    private var session: AccountSession?
    private var watchedRoomId: String?
    private var timeline: Timeline?
    private var handle: TaskHandle?
    private var watchTask: Task<Void, Never>?
    private var items: [TimelineItem] = []
    private let subscriptionOwner = UUID().uuidString

    private init() {
        roomIds = UserDefaults.standard.dictionary(forKey: Self.roomsKey) as? [String: String] ?? [:]
        showsNothingSent = !UserDefaults.standard.bool(forKey: Self.nothingSentKey)
    }

    /// The drafts room of an account, when known. The chat list, search, share and forward leave it out.
    func roomId(for userId: String) -> String? {
        roomIds[userId]
    }

    func isDraftsRoom(_ roomId: String, userId: String) -> Bool {
        roomIds[userId] == roomId
    }

    /// Reads chat.bipe.drafts_room for an account, without opening its timeline (the room lists of every account).
    @discardableResult
    func learnRoomId(session: AccountSession) async -> String? {
        guard let json = try? await session.client.accountData(eventType: BipeRegistries.draftsAccountDataType),
              let roomId = BipeAssistantDrafts.roomId(accountDataJson: json) else { return roomIds[session.userId] }
        if roomIds[session.userId] != roomId {
            roomIds[session.userId] = roomId
            UserDefaults.standard.set(roomIds, forKey: Self.roomsKey)
            revision &+= 1
        }
        return roomId
    }

    /// Action appear: Client.accountData, then the drafts room timeline. Each item goes through draftFromEvent.
    func refresh(session: AccountSession) async {
        if self.session !== session {
            stop()
            self.session = session
            userId = session.userId
        }
        guard let roomId = await learnRoomId(session: session), self.session === session else { return }
        guard watchedRoomId != roomId || timeline == nil else { return }
        stop()
        guard let room = try? session.client.getRoom(roomId: roomId), room.membership() == .joined,
              let timeline = try? await room.timeline(), self.session === session else { return }
        watchedRoomId = roomId
        self.timeline = timeline
        await session.roomSubscriptions.update(owner: subscriptionOwner, roomIDs: [roomId])
        let stream = TimelineDiffStream()
        handle = await timeline.addListener(listener: stream)
        watchTask = Task { [weak self] in
            for await diffs in stream.updates {
                guard let self else { return }
                for diff in diffs {
                    items.apply(diff.vectorDiff)
                }
                rebuild()
            }
        }
        // The drafts room is small: read back to its start, a few pages at most.
        Task {
            for _ in 0..<5 {
                guard let hitStart = try? await timeline.paginateBackwards(numEvents: 50), !hitStart else { break }
            }
        }
    }

    private func stop() {
        handle?.cancel()
        handle = nil
        watchTask?.cancel()
        watchTask = nil
        timeline = nil
        watchedRoomId = nil
        items = []
        drafts = []
        focused = [:]
        failedEventId = nil
        if let session {
            let subscriptions = session.roomSubscriptions
            let owner = subscriptionOwner
            Task { await subscriptions.remove(owner: owner) }
        }
    }

    private func rebuild() {
        guard let session else { return }
        let joined = Set(session.client.rooms().filter { $0.membership() == .joined }.map { $0.id() })
        var result: [Draft] = []
        for item in items {
            guard let event = item.asEvent(), case .eventId(let eventId) = event.eventOrTransactionId else { continue }
            var redacted = false
            if case .msgLike(let content) = event.content, case .redacted = content.kind { redacted = true }
            guard let draft = BipeAssistantDrafts.fromEventJson(event.lazyProvider.debugInfo().originalJson,
                                                                sender: event.sender, redacted: redacted,
                                                                ownUserId: session.userId, joinedRoomIds: joined).draft else { continue }
            let name = (try? session.client.getRoom(roomId: draft.roomId))?.displayName()?.nilIfBlank ?? BipeStrings.dmNoName
            result.append(Draft(eventId: eventId, roomId: draft.roomId, roomName: name, body: draft.body,
                                replyTo: draft.replyTo, threadRoot: draft.threadRoot, client: draft.client,
                                createdAt: Date(milliseconds: event.timestamp)))
        }
        // The timeline is oldest first.
        let newest = Array(result.reversed())
        if newest != drafts { drafts = newest }
        let ids = Set(newest.map(\.eventId))
        focused = focused.filter { ids.contains($0.value) }
        if let failedEventId, !ids.contains(failedEventId) { self.failedEventId = nil }
    }

    /// State currentRoomDrafts: the drafts of one chat of this account, newest first.
    func drafts(roomId: String, userId: String) -> [Draft] {
        guard self.userId == userId else { return [] }
        return drafts.filter { $0.roomId == roomId }
    }

    /// The draft the banner shows: the one opened from the list, else the newest.
    func banner(roomId: String, userId: String) -> Draft? {
        let current = drafts(roomId: roomId, userId: userId)
        if let eventId = focused[roomId], let draft = current.first(where: { $0.eventId == eventId }) { return draft }
        return current.first
    }

    /// Action openDraft: the banner of the target chat shows this draft.
    func open(_ draft: Draft) {
        focused[draft.roomId] = draft.eventId
    }

    /// Action send: body as the user's own message, with the reply and the thread, no AI marker (ADR 0210). After the
    /// send queue took it, the draft is redacted. On failure the draft stays.
    func send(_ draft: Draft) async {
        guard sendingEventId == nil, let session, let draftsTimeline = timeline else { return }
        sendingEventId = draft.eventId
        failedEventId = nil
        defer { sendingEventId = nil }
        do {
            guard let room = try session.client.getRoom(roomId: draft.roomId) else { throw ClientError.Generic(msg: "no room", details: nil) }
            let target: Timeline
            if let root = draft.threadRoot {
                target = try await room.timelineWithConfiguration(configuration: TimelineConfiguration(
                    focus: .thread(rootEventId: root), filter: .all, internalIdPrefix: "draft", dateDividerMode: .daily,
                    trackReadReceipts: .disabled, reportUtds: false))
            } else {
                target = try await room.timeline()
            }
            let content = ComposerMarkdown.content(text: draft.body, mentions: [])
            if let replyTo = draft.replyTo {
                try await target.sendReply(msg: content, eventId: replyTo)
            } else {
                _ = try await target.send(msg: content)
            }
            BipeHaptics.play(.tap)
        } catch {
            failedEventId = draft.eventId
            return
        }
        acknowledgeNothingSent()
        try? await draftsTimeline.redactEvent(eventOrTransactionId: .eventId(eventId: draft.eventId), reason: nil)
    }

    /// Action discard, and the last step of edit: Timeline.redactEvent on the draft. It goes on every device.
    func discard(_ draft: Draft) async {
        guard sendingEventId != draft.eventId, let timeline else { return }
        acknowledgeNothingSent()
        try? await timeline.redactEvent(eventOrTransactionId: .eventId(eventId: draft.eventId), reason: nil)
    }

    /// Action edit for a draft in a thread: the text becomes the thread's composer draft, which the thread view loads.
    func keepAsThreadDraft(_ draft: Draft) async {
        guard let root = draft.threadRoot, let room = try? session?.client.getRoom(roomId: draft.roomId) else { return }
        await room.keepDraft(draft.body, threadRoot: root)
    }

    private func acknowledgeNothingSent() {
        guard showsNothingSent else { return }
        showsNothingSent = false
        UserDefaults.standard.set(true, forKey: Self.nothingSentKey)
    }
}

/// Spec drafts: the banner above the composer for the draft of this chat.
struct AssistantDraftBanner: View {
    @ObservedObject var store: AssistantDraftsStore
    let roomId: String
    let userId: String
    /// Action edit: puts the draft in the composer (the caller), before the draft is redacted.
    let edit: (AssistantDraftsStore.Draft) async -> Void

    var body: some View {
        if let draft = store.banner(roomId: roomId, userId: userId) {
            let busy = store.sendingEventId != nil
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    PhosphorIcon.pencilSimple.image
                        .resizable()
                        .frame(width: 20, height: 20)
                        .foregroundStyle(BipeColor.accentInk)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(BipeStrings.appDraftsBanner(draft.client))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(BipeColor.accentInk)
                        Text(draft.body)
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkPrimary)
                            .lineLimit(6)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack(spacing: 16) {
                    Button(BipeStrings.appDraftsSend) { Task { await store.send(draft) } }
                        .font(.subheadline.weight(.semibold))
                        .disabled(busy)
                    Button(BipeStrings.appDraftsEdit) {
                        Task {
                            await edit(draft)
                            await store.discard(draft)
                        }
                    }
                    .font(.subheadline)
                    .disabled(busy)
                    Button(BipeStrings.appDraftsDiscard, role: .destructive) { Task { await store.discard(draft) } }
                        .font(.subheadline)
                        .disabled(busy)
                    if busy {
                        ProgressView()
                    }
                }
                .padding(.leading, 28)
                if store.failedEventId == draft.eventId {
                    Text(BipeStrings.appDraftsSendFailed)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .padding(.leading, 28)
                } else if store.showsNothingSent {
                    Text(BipeStrings.appDraftsNothingSent)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .padding(.leading, 28)
                }
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.surfaceRaised))
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(BipeScreen.drafts.rawValue)
        }
    }
}

/// Spec drafts openList: every draft of the account. A row opens its chat with that draft in the banner.
struct AssistantDraftsList: View {
    @ObservedObject var store: AssistantDraftsStore
    let userId: String
    let openChat: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                let drafts = store.userId == userId ? store.drafts : []
                if drafts.isEmpty {
                    Text(BipeStrings.appDraftsEmpty)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .multilineTextAlignment(.center)
                        .padding(24)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(drafts) { draft in
                        Button {
                            store.open(draft)
                            dismiss()
                            openChat(draft.roomId)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(draft.roomName)
                                    .font(.headline)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Text(draft.body)
                                    .font(.subheadline)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                    .lineLimit(2)
                                Text(BipeStrings.appDraftsBanner(draft.client))
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appDraftsTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.drafts.rawValue)
    }
}
