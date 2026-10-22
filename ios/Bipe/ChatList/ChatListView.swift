// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Phosphor
import SwiftUI

/// Spec chat-list, one scope: Conversas or Grupos (ADR 0053).
struct ChatListView: View {
    @ObservedObject private var model: ChatListModel
    @ObservedObject private var notifications = NotificationActions.shared
    @StateObject private var handle: HandlePickModel
    @StateObject private var encryption: EncryptionStatusModel
    @State private var path: [String]
    @State private var showsHandlePick = false
    private let scope: ChatScope
    private let account: AccountSummary?
    private let onOpenVoce: () -> Void
    private let api: BipeAPIClient
    @State private var showingInvites = false
    @State private var creatingGroup = false
    @State private var deletingDm: String?
    @ObservedObject private var cleared = ClearedChatsStore.shared

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    init(model: ChatListModel, scope: ChatScope, api: BipeAPIClient, initialRoomId: String? = nil, account: AccountSummary? = nil, onOpenVoce: @escaping () -> Void = {}) {
        self.account = account
        self.onOpenVoce = onOpenVoce
        self.api = api
        _model = ObservedObject(wrappedValue: model)
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: model.session))
        _encryption = StateObject(wrappedValue: EncryptionStatusModel(session: model.session))
        _path = State(initialValue: initialRoomId.map { [$0] } ?? [])
        self.scope = scope
    }

    var body: some View {
        NavigationStack(path: $path) {
            VStack(spacing: 0) {
                // Spec encryption-status: the banner above the chat list.
                if scope == .conversas && encryption.showsBanner {
                    HistoryBanner(model: encryption)
                }
                content
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(scope == .conversas ? BipeStrings.appChatListTitle : BipeStrings.tabGroups)
            .navigationDestination(for: String.self) { roomId in
                ChatTimelineView(session: model.session, media: model.media, roomId: roomId)
            }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    CreateGroupButton(createGroup: { creatingGroup = true }, invite: { showingInvites = true })
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if let account {
                        Button(action: onOpenVoce) {
                            AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: model.media,
                                       size: 32, userId: account.userId, isCompany: account.isCompany)
                        }
                        .accessibilityLabel(BipeStrings.appAccountCurrentA11y(account.displayName))
                    }
                }
            }
        }
        .groupHost(session: model.session, api: api, media: model.media, path: $path, creatingGroup: $creatingGroup,
                   deletingDm: $deletingDm, showingInvites: $showingInvites)
        .environment(\.openHistoryHelp, encryption.openSheet)
        .sheet(item: $encryption.route, onDismiss: encryption.sheetClosed) { route in
            EncryptionSheet(route: route, model: encryption, session: model.session)
        }
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task { await encryption.observe() }
        .task {
            if scope == .conversas {
                await handle.load()
            }
        }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
        }
        // Spec notification-service, action open.
        .onReceive(notifications.$roomToOpen.compactMap { $0 }) { roomId in
            notifications.roomToOpen = nil
            path = [roomId]
        }
    }

    @ViewBuilder
    private var handlePrompt: some View {
        if scope == .conversas && handle.showsPrompt {
            HandlePromptCard(model: handle) { showsHandlePick = true }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            handlePrompt
            list
        }
    }

    @ViewBuilder
    private var list: some View {
        switch model.phase(in: scope) {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            empty
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            let chats = model.chats(in: scope)
            List {
                ForEach(chats.filter { !cleared.hidden(roomId: $0.roomId, latest: $0.lastActivity) }) { chat in
                    Button {
                        openChat(chat.roomId)
                    } label: {
                        ChatRow(chat: chat, media: model.media)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                    .swipeActions(edge: .trailing) {
                        if chat.isDm {
                            Button(BipeStrings.dmDeleteAction, role: .destructive) {
                                deletingDm = chat.roomId
                            }
                        }
                    }
                    .onAppear {
                        if chat.id == chats.last?.id {
                            model.loadMore()
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    @ViewBuilder
    private var empty: some View {
        switch scope {
        case .conversas:
            Text(BipeStrings.appChatListEmpty)
                .foregroundStyle(BipeColor.inkSecondary)
        case .grupos:
            VStack(spacing: 8) {
                Text(BipeStrings.groupsEmptyTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.groupsEmptyBody)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            .multilineTextAlignment(.center)
            .padding(24)
        }
    }

    /// Action openChat.
    private func openChat(_ roomId: String) {
        path.append(roomId)
    }
}

struct ChatRow: View {
    let chat: ChatSummary
    let media: MediaLoader

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, userId: chat.peerUserId)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline) {
                    Text(chat.name)
                        .font(.headline)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if let lastActivity = chat.lastActivity {
                        Text(lastActivity, format: Self.timeFormat(for: lastActivity))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                }
                HStack {
                    Text(chat.lastMessage ?? "")
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    // Spec chat-notifications: a muted chat keeps its badge, without sound or notification.
                    if chat.isMuted {
                        PhosphorIcon.bellSlash.image
                            .resizable()
                            .frame(width: 16, height: 16)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .accessibilityLabel(BipeStrings.appNotificationModeOff)
                    }
                    if chat.unreadCount > 0 {
                        let count = chat.unreadCount.formatted()
                        Text(count)
                            .font(.caption.bold())
                            .foregroundStyle(BipeColor.inkOnAccent)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(BipeColor.accentFill))
                            .accessibilityLabel(BipeStrings.appChatListUnread(count))
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private static func timeFormat(for date: Date) -> Date.FormatStyle {
        Calendar.current.isDateInToday(date)
            ? .dateTime.hour().minute()
            : .dateTime.day().month(.abbreviated)
    }
}
