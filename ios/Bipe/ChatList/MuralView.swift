// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-list, Mural tiles and grid rules (ADR 0246): the rows of the tile grid, for a List. One grid line is one List
/// row. The tier sets the tile; the last line of a tier stretches, because its tiles share the full width.
struct MuralTileRows<MenuContent: View>: View {
    @ObservedObject var model: MuralModel
    /// The chats that can have a tile now. A tile of a chat that was archived goes at once, before the next plan.
    let chats: [String: ChatSummary]
    let media: MediaLoader
    let selectedRoomId: String?
    let open: (String) -> Void
    let openList: () -> Void
    @ViewBuilder let menu: (String) -> MenuContent
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private struct Line: Identifiable {
        let tiles: [MuralTile]
        var id: String { tiles.map(\.roomId).joined(separator: "|") }
    }

    private var visibleTiles: [MuralTile] { model.tiles.filter { chats[$0.roomId] != nil } }

    /// The tile lines of tiers 1 to 3. Under the accessibility text sizes a line has one tile less, so the text fits.
    private func lines(_ shown: [MuralTile]) -> [Line] {
        BipeHomeScore.tiers.flatMap { rule -> [Line] in
            guard let columns = rule.columns else { return [] }
            let perLine = dynamicTypeSize.isAccessibilitySize ? max(1, columns - 1) : columns
            let tiles = shown.filter { $0.tier == rule.tier }
            return stride(from: 0, to: tiles.count, by: perLine).map { start in
                Line(tiles: Array(tiles[start..<min(start + perLine, tiles.count)]))
            }
        }
    }

    var body: some View {
        let shown = visibleTiles
        let firstRoomId = shown.first { $0.tier == 1 }?.roomId
        ForEach(lines(shown)) { line in
            HStack(alignment: .top, spacing: 8) {
                ForEach(line.tiles) { tile in
                    tileView(tile, isFirst: tile.roomId == firstRoomId)
                }
            }
            // Tiles of one line have one height.
            .fixedSize(horizontal: false, vertical: true)
            .muralRow()
        }
        let quiet = shown.filter { $0.tier == 4 }
        if !quiet.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text(BipeStrings.chatMuralNoNews)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(BipeColor.inkSecondary)
                    .accessibilityAddTraits(.isHeader)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 52), spacing: 8)], alignment: .leading, spacing: 8) {
                    ForEach(quiet) { tile in
                        if let chat = chats[tile.roomId] {
                            tileMenu(tile, chat: chat, messages: []) {
                                MuralQuietFace(chat: chat, media: media, isSelected: selectedRoomId == chat.roomId)
                            }
                        }
                    }
                }
            }
            .padding(.top, 8)
            .muralRow()
        }
        // Spec chat-list openMuralList: this row ends the screen.
        Button(action: openList) {
            ListEntryRow(icon: .caretRight, title: BipeStrings.chatMuralViewList)
        }
        .buttonStyle(.plain)
        .listRowSeparator(.hidden)
        .listRowBackground(BipeColor.surfaceBase)
    }

    private func tileView(_ tile: MuralTile, isFirst: Bool) -> some View {
        Group {
            if let chat = chats[tile.roomId] {
                let texts = messages(of: chat, tile: tile, isFirst: isFirst)
                VStack(spacing: 0) {
                    tileMenu(tile, chat: chat, messages: texts) {
                        MuralTileContent(tile: tile, chat: chat, messages: texts, media: media)
                    }
                    // The reply field is outside the Menu: a tap on it does not open the chat (spec chat-list, Mural tiles).
                    if tile.roomId == model.replyRoomId {
                        MuralReplyField(model: model)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .fill(tile.tier == 1 ? BipeColor.accentWash : BipeColor.surfaceRaised))
                .overlay {
                    if selectedRoomId == chat.roomId {
                        RoundedRectangle(cornerRadius: 22, style: .continuous).stroke(BipeColor.accentFill, lineWidth: 2)
                    }
                }
            }
        }
    }

    /// Spec chat-list, Mural tiles: the number of messages comes from the tier. Before the local read, the row text.
    private func messages(of chat: ChatSummary, tile: MuralTile, isFirst: Bool) -> [String] {
        let count = switch tile.tier {
        case 1: isFirst ? BipeHomeScore.firstTileMessages : BipeHomeScore.secondTileMessages
        case 2: BipeHomeScore.tier2Bubbles
        default: 1
        }
        let read = Array((model.messages[chat.roomId] ?? []).suffix(count))
        if !read.isEmpty { return read }
        return chat.lastMessage.map { [$0] } ?? []
    }

    /// Tap opens the chat, long press the actions of chat-row-actions. A Menu, not .contextMenu: a List row with several
    /// tiles shows the first .contextMenu of the row for all of them (the same as the favourites grid).
    private func tileMenu<Face: View>(_ tile: MuralTile, chat: ChatSummary, messages: [String],
                                      @ViewBuilder face: () -> Face) -> some View {
        let shownFace = face()
        return Menu { menu(chat.roomId) } label: { shownFace } primaryAction: { open(chat.roomId) }
            .menuIndicator(.hidden)
            .menuStyle(.button)
            .buttonStyle(.plain)
            .simultaneousGesture(LongPressGesture().onEnded { _ in
                BipeHaptics.play(.hold)
                model.holdForMenu()
            })
            .accessibilityActions { menu(chat.roomId) }
            .accessibilityLabel(MuralLabel.text(chat: chat, reason: tile.reason, seal: sealLabel(chat)))
            .accessibilityValue(messages.joined(separator: ". "))
    }

    @Environment(\.bipeCompanySeals) private var seals

    private func sealLabel(_ chat: ChatSummary) -> String? {
        guard chat.isDm, let peer = chat.peerUserId, let mark = seals?.mark(for: peer) else { return nil }
        return SealMark.label(mark)
    }
}

private extension View {
    /// A grid line as a List row: no separator, the screen colour behind it.
    func muralRow() -> some View {
        listRowSeparator(.hidden)
            .listRowBackground(BipeColor.surfaceBase)
            .listRowInsets(EdgeInsets(top: 4, leading: 10, bottom: 4, trailing: 10))
    }
}

/// Spec chat-list, Mural grid rules: a screen reader reads name, reason and unread count.
private enum MuralLabel {
    static func text(chat: ChatSummary, reason: BipeHomeReason?, seal: String?) -> String {
        var parts = [chat.name]
        if chat.isOfficial { parts.append(BipeStrings.badgeOfficialA11y) }
        if let seal { parts.append(seal) }
        if let reason = reason.map(MuralReason.text) { parts.append(reason) }
        if chat.unreadCount > 0 {
            parts.append(BipeStrings.appChatListUnread(chat.unreadCount.formatted()))
            if chat.mentionCount > 0 { parts.append(BipeStrings.a11yMention) }
        } else if chat.isMarkedUnread {
            parts.append(BipeStrings.chatUnreadState)
        }
        return parts.joined(separator: ", ")
    }
}

private enum MuralReason {
    static func text(_ reason: BipeHomeReason) -> String {
        switch reason {
        case .mention: BipeStrings.chatMuralReasonMention
        case .unanswered: BipeStrings.chatMuralReasonUnanswered
        }
    }
}

/// The inside of a tile of tier 1, 2 or 3. It fills the tile, so the whole tile takes the tap.
private struct MuralTileContent: View {
    let tile: MuralTile
    let chat: ChatSummary
    let messages: [String]
    let media: MediaLoader

    var body: some View {
        Group {
            switch tile.tier {
            case 1: large
            case 2: medium
            default: small
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: tile.tier == 3 ? .top : .topLeading)
        .contentShape(Rectangle())
    }

    /// Tier 1: avatar and name, then the messages.
    private var large: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                avatar(56)
                VStack(alignment: .leading, spacing: 2) {
                    name(.headline, chat.name)
                    BrandMarkCaption(userId: chat.peerUserId)
                }
                Spacer(minLength: 8)
                if let lastActivity = chat.lastActivity {
                    Text(lastActivity, format: ChatRow.timeFormat(for: lastActivity))
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .monospacedDigit()
                        .fixedSize(horizontal: true, vertical: false)
                }
                ChatRowBadges(chat: chat)
            }
            ForEach(Array(messages.enumerated()), id: \.offset) { _, message in
                bubble(message, lines: 3)
            }
        }
        .padding(14)
    }

    /// Tier 2: avatar, name, reason and one bubble.
    private var medium: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 4) {
                avatar(44)
                Spacer(minLength: 4)
                ChatRowBadges(chat: chat)
            }
            VStack(alignment: .leading, spacing: 2) {
                name(.subheadline.weight(.semibold), chat.name)
                if let reason = tile.reason {
                    Text(MuralReason.text(reason))
                        .font(.caption)
                        .foregroundStyle(BipeColor.accentInk)
                        .lineLimit(1)
                }
            }
            ForEach(Array(messages.enumerated()), id: \.offset) { _, message in
                bubble(message, lines: 3)
            }
        }
        .padding(12)
    }

    /// Tier 3: avatar, first word of the name, two lines of text.
    private var small: some View {
        VStack(spacing: 4) {
            avatar(44)
            name(.footnote.weight(.semibold), firstWord)
            Text(messages.last ?? "")
                .font(.caption)
                .foregroundStyle(BipeColor.inkSecondary)
                .multilineTextAlignment(.center)
                .lineLimit(BipeHomeScore.tier3TextLines)
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .topTrailing) {
            HStack(spacing: 2) { ChatRowBadges(chat: chat) }
                .padding(6)
        }
    }

    private var firstWord: String {
        chat.name.split(separator: " ").first.map(String.init) ?? chat.name
    }

    private func avatar(_ size: CGFloat) -> some View {
        AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: size, userId: chat.peerUserId,
                   members: chat.avatarMembers)
    }

    private func name(_ font: Font, _ text: String) -> some View {
        HStack(spacing: 4) {
            Text(text)
                .font(font)
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(1)
            if chat.isOfficial { OfficialBadge() }
            if chat.isDm { SealMark(userId: chat.peerUserId) }
        }
    }

    private func bubble(_ text: String, lines: Int) -> some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(BipeColor.inkPrimary)
            .lineLimit(lines)
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(BipeColor.bubbleOther))
    }
}

/// Tier 4: the avatar only, with its seal and a mark when the chat is unread.
private struct MuralQuietFace: View {
    let chat: ChatSummary
    let media: MediaLoader
    let isSelected: Bool

    var body: some View {
        AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: 44, userId: chat.peerUserId,
                   members: chat.avatarMembers)
            .overlay(alignment: .topTrailing) {
                if chat.isUnread {
                    Circle().fill(BipeColor.accentFill)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().stroke(BipeColor.surfaceBase, lineWidth: 2))
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if chat.isDm { SealMark(userId: chat.peerUserId, size: 14) }
            }
            .padding(4)
            .overlay {
                if isSelected { Circle().stroke(BipeColor.accentFill, lineWidth: 2) }
            }
            .bipeTapTarget()
    }
}

/// The reply field of the first tile (spec chat-list, action replyFromTile).
private struct MuralReplyField: View {
    @ObservedObject var model: MuralModel
    @FocusState private var focused: Bool

    private var canSend: Bool {
        !model.sending && !model.replyText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let draft = model.savedDraft, model.replyText == draft {
                Text(BipeStrings.appChatListDraft(draft))
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .lineLimit(1)
            }
            if model.replyFailed {
                Text(BipeStrings.errorGeneric)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
            HStack(alignment: .bottom, spacing: 8) {
                TextField(BipeStrings.chatsMessagePlaceholder, text: $model.replyText, axis: .vertical)
                    .lineLimit(1...4)
                    .focused($focused)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .frame(minHeight: BipeTapTarget.minimum)
                    .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(BipeColor.surfaceBase))
                Button {
                    Task {
                        if await model.sendReply() { focused = false }
                    }
                } label: {
                    PhosphorIcon.paperPlaneRight.image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .foregroundStyle(canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                        .bipeTapTarget()
                        .background(Circle().fill(canSend ? BipeColor.accentFill : BipeColor.surfaceBase))
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .accessibilityLabel(BipeStrings.appTimelineSend)
            }
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 14)
        .onChange(of: focused) { _, isFocused in model.setHold(.reply, isFocused) }
        .onDisappear { model.setHold(.reply, false) }
    }
}
