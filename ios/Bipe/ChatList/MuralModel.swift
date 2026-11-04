// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The Mural setting (spec chat-list, settings-group row chats.mural): per account, on this device, default off. It does
/// not sync and it is not in account data.
enum MuralSetting {
    static func key(userId: String) -> String { "bipe.chats.mural.\(userId)" }
}

/// One tile of the plan (spec chat-list, type MuralTile). The chat and its texts are read live, so they change in place.
struct MuralTile: Identifiable, Equatable {
    let roomId: String
    let tier: Int
    let reason: BipeHomeReason?

    var id: String { roomId }
}

/// Spec chat-list, Mural (ADR 0246): the plan from BipeHomeScore, held while the screen is not at rest, and the reply
/// field of the first tile. No score or signal leaves the phone.
@MainActor
final class MuralModel: ObservableObject {
    /// Why the plan cannot change now.
    enum Hold: Hashable {
        case scroll
        /// The reply field has focus: the first tile must stay the chat that the person is writing to.
        case reply
        case send
        /// A long press opened the actions of a tile.
        case menu
    }

    /// SwiftUI tells nothing when a Menu closes, so a long press holds the plan for this long.
    private static let menuHold: Duration = .seconds(10)
    /// Time for the composer of the closed chat to save its draft (spec composer leave).
    private static let draftSettle: Duration = .milliseconds(300)

    @Published private(set) var tiles: [MuralTile] = []
    /// The newest message previews of each room, oldest first.
    @Published private(set) var messages: [String: [String]] = [:]
    /// The room of the reply field: the first tile.
    @Published private(set) var replyRoomId: String?
    /// State muralReplyText of spec chat-list.
    @Published var replyText = ""
    /// Type MuralTile draft: the saved composer draft of the room.
    @Published private(set) var savedDraft: String?
    @Published private(set) var sending = false
    @Published private(set) var replyFailed = false

    private let chatList: ChatListModel
    private var rooms: [BipeHomeInputs.Room] = []
    /// The last events read for each room. The scanner has none while it reads a changed room again, and a tile must not
    /// drop for that time.
    private var knownEvents: [String: [BipeRowActivity.LocalEvent]] = [:]
    private var holds: Set<Hold> = []
    private var planIsStale = false
    private var menuHoldTask: Task<Void, Never>?
    /// False until the saved draft of the reply room is in the field: an empty field must not clear that draft.
    private var draftLoaded = false

    init(chatList: ChatListModel) {
        self.chatList = chatList
    }

    deinit {
        menuHoldTask?.cancel()
    }

    nonisolated static func milliseconds(_ date: Date) -> UInt64 {
        UInt64(max(0, date.timeIntervalSince1970 * 1000))
    }

    /// New facts of the rooms. New text shows at once; a new plan waits for the screen to be at rest.
    func update(_ rooms: [BipeHomeInputs.Room]) {
        self.rooms = rooms
        var previews: [String: [String]] = [:]
        for room in rooms {
            if let events = chatList.localEvents(roomId: room.id) { knownEvents[room.id] = events }
            previews[room.id] = BipeHomeInputs.previews(knownEvents[room.id] ?? [], limit: BipeHomeScore.firstTileMessages)
        }
        if previews != messages { messages = previews }
        replan()
    }

    func setHold(_ hold: Hold, _ isOn: Bool) {
        if isOn {
            holds.insert(hold)
        } else {
            holds.remove(hold)
            if holds.isEmpty && planIsStale { replan() }
        }
    }

    /// A long press opened the actions of a tile.
    func holdForMenu() {
        setHold(.menu, true)
        menuHoldTask?.cancel()
        menuHoldTask = Task { [weak self] in
            try? await Task.sleep(for: Self.menuHold)
            guard !Task.isCancelled else { return }
            self?.setHold(.menu, false)
        }
    }

    private func replan() {
        guard holds.isEmpty else {
            planIsStale = true
            return
        }
        planIsStale = false
        let now = Self.milliseconds(.now)
        let chats = rooms.map { BipeHomeInputs.chat($0, events: knownEvents[$0.id] ?? [], nowMs: now) }
        let next = BipeHomeScore.plan(chats).compactMap { tile in
            tile.tier.map { MuralTile(roomId: tile.id, tier: $0, reason: tile.reason) }
        }
        if next != tiles { tiles = next }
        followFirstTile()
    }

    // MARK: - Reply field

    /// The first tile is another chat: the text stays as the draft of its room, and the field takes the draft of the new one.
    private func followFirstTile() {
        let next = tiles.first { $0.tier == 1 }?.roomId
        guard next != replyRoomId else { return }
        keepReply()
        replyRoomId = next
        replyText = ""
        savedDraft = nil
        replyFailed = false
        draftLoaded = false
        Task { await loadDraft() }
    }

    private func loadDraft() async {
        guard let roomId = replyRoomId, let room = try? chatList.session.client.getRoom(roomId: roomId) else { return }
        let saved = await room.newMessageDraft()
        guard replyRoomId == roomId else { return }
        savedDraft = saved
        // Text typed during the read stays.
        if replyText.isEmpty { replyText = saved ?? "" }
        draftLoaded = true
    }

    /// Saves the field as the draft of its room, the same draft that the composer of the chat shows.
    /// An unchanged field writes nothing: the composer of the open chat can hold a newer draft.
    func keepReply() {
        guard draftLoaded, replyText != savedDraft ?? "", let roomId = replyRoomId,
              let room = try? chatList.session.client.getRoom(roomId: roomId) else { return }
        let text = replyText
        savedDraft = text.isEmpty ? nil : text
        Task { await room.keepDraft(text) }
    }

    /// The composer of the chat can have changed or sent the draft.
    func reloadDraft() {
        guard !holds.contains(.reply) else { return }
        draftLoaded = false
        Task {
            try? await Task.sleep(for: Self.draftSettle)
            guard !holds.contains(.reply) else {
                draftLoaded = true
                return
            }
            replyText = ""
            await loadDraft()
        }
    }

    /// Action replyFromTile of spec chat-list. True when the message went into the send queue. A failed send keeps the text.
    func sendReply() async -> Bool {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !sending, let roomId = replyRoomId else { return false }
        sending = true
        replyFailed = false
        setHold(.send, true)
        defer {
            sending = false
            setHold(.send, false)
        }
        do {
            guard let room = try chatList.session.client.getRoom(roomId: roomId) else {
                throw ClientError.Generic(msg: "no room", details: nil)
            }
            let timeline = try await room.timeline()
            // Spec composer send: the same content as the composer, here with no pills.
            _ = try await timeline.send(msg: ComposerMarkdown.content(text: text, mentions: []))
            BipeHaptics.play(.tap)
            if replyRoomId == roomId {
                replyText = ""
                savedDraft = nil
            }
            try? await room.clearComposerDraft(threadRoot: nil)
            await chatList.setUnread(roomId: roomId, unread: false)
            return true
        } catch {
            replyFailed = true
            return false
        }
    }
}
