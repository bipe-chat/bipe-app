// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import SwiftUI

/// Spec work-notes and the reply-by events of work-chat-bar (bipe ADR 0284 points 18, 19 and 22;
/// registries/work-notes.json): the notes room of a work account. Found like the drafts room (AssistantDraftsStore),
/// through the global account data chat.bipe.work_notes_room; created on first use like the self chat (NotesRoom).
@MainActor
final class WorkNotesStore: ObservableObject {
    static let shared = WorkNotesStore()

    /// One note on a customer chat.
    struct Note: Identifiable, Equatable {
        let eventId: String
        let roomId: String
        let body: String
        /// The author's seat, when known.
        let seatId: String?
        let createdAt: Date

        var id: String { eventId }
    }

    /// Valid notes of every customer chat, oldest first.
    @Published private(set) var notes: [Note] = []
    /// Customer event ID to its valid reply-by event.
    @Published private(set) var replyBy: [String: BipeWorkNotes.ReplyBy] = [:]
    /// Goes up when a notes room ID becomes known, so the room lists hide it.
    @Published private(set) var revision: UInt64 = 0
    /// The account whose notes are shown.
    private(set) var userId: String?

    private static let roomsKey = "bipe.workNotes.rooms"
    private static func pendingKey(_ userId: String) -> String { "bipe.workNotes.pending.\(userId)" }
    /// User ID to notes room ID, kept so the lists hide the room before the account data is read again.
    private var roomIds: [String: String]
    private var session: AccountSession?
    private var watchedRoomId: String?
    private var timeline: Timeline?
    private var handle: TaskHandle?
    private var watchTask: Task<Void, Never>?
    private var items: [TimelineItem] = []
    private var opening: Task<String, Error>?
    private var seatIds: Set<String> = []
    private let subscriptionOwner = UUID().uuidString
    private enum Failure: Error { case unavailable }

    private init() {
        roomIds = UserDefaults.standard.dictionary(forKey: Self.roomsKey) as? [String: String] ?? [:]
    }

    /// The notes room of an account, when known.
    func roomId(for userId: String) -> String? {
        roomIds[userId]
    }

    /// Reads chat.bipe.work_notes_room, without opening the timeline.
    @discardableResult
    func learnRoomId(session: AccountSession) async -> String? {
        guard let json = try? await session.client.accountData(eventType: BipeRegistries.workNotesAccountDataType),
              let roomId = BipeWorkNotes.roomId(accountDataJson: json) else { return roomIds[session.userId] }
        remember(roomId, userId: session.userId)
        return roomId
    }

    private func remember(_ roomId: String, userId: String) {
        guard roomIds[userId] != roomId else { return }
        roomIds[userId] = roomId
        UserDefaults.standard.set(roomIds, forKey: Self.roomsKey)
        revision &+= 1
    }

    /// Reads the notes room and follows its timeline. seatIds: every seat of the account, for replyByFromEvent.
    func refresh(session: AccountSession, seatIds: Set<String>) async {
        if self.session !== session {
            stop()
            self.session = session
            userId = session.userId
        }
        if self.seatIds != seatIds {
            self.seatIds = seatIds
            rebuild()
        }
        guard let roomId = await learnRoomId(session: session), self.session === session else { return }
        await watch(roomId: roomId, session: session)
    }

    private func watch(roomId: String, session: AccountSession) async {
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
        // Reply-by events add up: read back a few pages, so the names show on the recent replies.
        Task {
            for _ in 0..<10 {
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
        notes = []
        replyBy = [:]
        if let session {
            let subscriptions = session.roomSubscriptions
            let owner = subscriptionOwner
            Task { await subscriptions.remove(owner: owner) }
        }
    }

    private func rebuild() {
        guard let session else { return }
        let joined = Set(session.client.rooms().filter { $0.membership() == .joined }.map { $0.id() })
        var found: [Note] = []
        var names: [String: BipeWorkNotes.ReplyBy] = [:]
        for item in items {
            guard let event = item.asEvent(), case .eventId(let eventId) = event.eventOrTransactionId else { continue }
            var redacted = false
            if case .msgLike(let content) = event.content, case .redacted = content.kind { redacted = true }
            let json = event.lazyProvider.debugInfo().originalJson
            if let note = BipeWorkNotes.noteFromEventJson(json, sender: event.sender, redacted: redacted,
                                                          ownUserId: session.userId, joinedRoomIds: joined,
                                                          seatIds: seatIds).value {
                found.append(Note(eventId: eventId, roomId: note.roomId, body: note.body, seatId: note.seatId,
                                  createdAt: Date(milliseconds: event.timestamp)))
            } else if let reply = BipeWorkNotes.replyByFromEventJson(json, sender: event.sender, redacted: redacted,
                                                                     ownUserId: session.userId, seatIds: seatIds).value {
                names[reply.eventId] = reply
            }
        }
        if found != notes { notes = found }
        if names != replyBy { replyBy = names }
    }

    /// State notes of work-notes: the notes of one customer chat, oldest first.
    func notes(roomId: String, userId: String) -> [Note] {
        guard self.userId == userId else { return [] }
        return notes.filter { $0.roomId == roomId }
    }

    /// The notes room, created on first use: encrypted, private, with only the account's own user, its ID in account
    /// data, and muted (ADR 0284 point 22). Calls at the same time share one creation.
    func open(session: AccountSession) async throws -> String {
        if let opening { return try await opening.value }
        let task = Task { [weak self] () throws -> String in
            guard let self else { throw Failure.unavailable }
            return try await self.openOrCreate(session: session)
        }
        opening = task
        defer { opening = nil }
        let roomId = try await task.value
        if self.session === session { await watch(roomId: roomId, session: session) }
        return roomId
    }

    private func openOrCreate(session: AccountSession) async throws -> String {
        // A failed read must never count as "no notes room yet".
        let json = try await session.client.accountData(eventType: BipeRegistries.workNotesAccountDataType)
        let pendingKey = Self.pendingKey(session.userId)
        let pending = UserDefaults.standard.string(forKey: pendingKey)
        if let roomId = BipeWorkNotes.roomId(accountDataJson: json), roomId != pending {
            remember(roomId, userId: session.userId)
            if (try? session.client.getRoom(roomId: roomId)) == nil {
                _ = try await session.client.awaitRoomRemoteEcho(roomId: roomId)
            }
            guard let room = try? session.client.getRoom(roomId: roomId),
                  try await room.roomInfo().membership == .joined else { throw Failure.unavailable }
            return roomId
        }
        if json != nil, BipeWorkNotes.roomId(accountDataJson: json) == nil { throw Failure.unavailable }
        let id: String
        if let pending {
            id = pending
        } else {
            // The SDK's CreateRoomParameters has no creation type: the room goes without roomType (registries/work-notes.json).
            id = try await session.client.createRoom(request: CreateRoomParameters(
                name: BipeStrings.chatNotesTitle, isEncrypted: true, isDirect: false,
                visibility: .private, preset: .privateChat, invite: nil))
            // If the account data write fails, the next try finishes it instead of creating another room.
            UserDefaults.standard.set(id, forKey: pendingKey)
        }
        try await session.client.setAccountData(eventType: BipeRegistries.workNotesAccountDataType,
                                                content: BipeWorkNotes.accountDataContent(roomId: id))
        UserDefaults.standard.removeObject(forKey: pendingKey)
        remember(id, userId: session.userId)
        _ = try await session.client.awaitRoomRemoteEcho(roomId: id)
        let settings = await session.client.getNotificationSettings()
        try? await settings.setRoomNotificationMode(roomId: id, mode: .mute)
        return id
    }

    /// Action send of work-notes: an m.text with the note field and this device's seat. false when it failed.
    func sendNote(_ body: String, chatRoomId: String, seatId: String?, session: AccountSession) async -> Bool {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.utf16.count <= BipeRegistries.workNoteBodyMaxLength else { return false }
        do {
            let id = try await open(session: session)
            guard let room = try session.client.getRoom(roomId: id) else { return false }
            try await room.sendRaw(eventType: "m.room.message", content: BipeWorkNotes.noteContent(body: text, roomId: chatRoomId, seatId: seatId))
            return true
        } catch {
            return false
        }
    }

    /// Action reply of work-chat-bar: after a send to a customer, one reply-by notice (an m.room.message, msgtype
    /// m.notice). A failure is silent: the reply then shows no name.
    func sendReplyBy(chatRoomId: String, eventId: String, seatId: String, session: AccountSession) async {
        guard let id = try? await open(session: session), let room = try? session.client.getRoom(roomId: id) else { return }
        try? await room.sendRaw(eventType: "m.room.message",
                                content: BipeWorkNotes.replyByContent(roomId: chatRoomId, eventId: eventId, seatId: seatId))
    }
}

/// Spec work-notes: the internal notes of one customer chat, in a sheet.
struct WorkNotesSheet: View {
    @ObservedObject var store: WorkNotesStore
    let session: AccountSession
    let roomId: String
    @State private var draft = ""
    @State private var phase: Phase = .ready
    @State private var accountName = ""
    @Environment(\.dismiss) private var dismiss

    private enum Phase { case ready, sending, failed }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                let notes = store.notes(roomId: roomId, userId: session.userId)
                List {
                    Section {
                        Text(BipeStrings.chatNotesHint(accountName))
                            .font(.footnote)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    if notes.isEmpty {
                        Text(BipeStrings.chatNotesEmpty)
                            .foregroundStyle(BipeColor.inkSecondary)
                    } else {
                        ForEach(notes) { note in
                            VStack(alignment: .leading, spacing: 4) {
                                if let author = WorkQueueStore.shared.seatName(note.seatId) {
                                    Text(author)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(BipeColor.inkSecondary)
                                }
                                Text(note.body)
                                    .font(.body)
                                    .foregroundStyle(BipeColor.inkPrimary)
                                Text(note.createdAt, format: .dateTime.day().month(.abbreviated).hour().minute())
                                    .font(.caption)
                                    .foregroundStyle(BipeColor.inkSecondary)
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                if phase == .failed {
                    Text(BipeStrings.chatNotesError)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.trustDangerInk)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                }
                HStack(alignment: .bottom, spacing: 8) {
                    TextField(BipeStrings.chatNotesPlaceholder, text: $draft, axis: .vertical)
                        .lineLimit(1...5)
                        .textFieldStyle(.roundedBorder)
                    Button(BipeStrings.appTimelineSend) { Task { await send() } }
                        .buttonStyle(.bipeProminent)
                        .disabled(!canSend)
                }
                .padding(12)
                .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.chatNotesTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
            .task {
                accountName = (try? await session.client.displayName()) ?? ""
                await store.refresh(session: session, seatIds: WorkQueueStore.shared.seatIds)
            }
        }
        .accessibilityIdentifier(BipeScreen.workNotes.rawValue)
    }

    private var canSend: Bool {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        return phase != .sending && !text.isEmpty && text.utf16.count <= BipeRegistries.workNoteBodyMaxLength
    }

    private func send() async {
        guard canSend else { return }
        phase = .sending
        if await store.sendNote(draft, chatRoomId: roomId, seatId: WorkQueueStore.shared.mySeatId,
                                     session: session) {
            draft = ""
            phase = .ready
        } else {
            phase = .failed
        }
    }
}
