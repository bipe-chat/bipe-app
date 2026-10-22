// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-list, one scope: Conversas or Grupos (ADR 0053).
struct ChatListView: View {
    @ObservedObject private var model: ChatListModel
    @StateObject private var handle: HandlePickModel
    @State private var path: [String]
    @State private var showsHandlePick = false
    private let scope: ChatScope
    private let account: AccountSummary?
    private let onOpenVoce: () -> Void

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    init(model: ChatListModel, scope: ChatScope, api: BipeAPIClient, initialRoomId: String? = nil, account: AccountSummary? = nil, onOpenVoce: @escaping () -> Void = {}) {
        self.account = account
        self.onOpenVoce = onOpenVoce
        _model = ObservedObject(wrappedValue: model)
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: model.session))
        _path = State(initialValue: initialRoomId.map { [$0] } ?? [])
        self.scope = scope
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(scope == .conversas ? BipeStrings.appChatListTitle : BipeStrings.tabGroups)
                .navigationDestination(for: String.self) { roomId in
                    ChatTimelineView(session: model.session, media: model.media, roomId: roomId)
                }
                .toolbar {
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
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task {
            if scope == .conversas {
                await handle.load()
            }
        }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
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
                ForEach(chats) { chat in
                    Button {
                        openChat(chat.roomId)
                    } label: {
                        ChatRow(chat: chat, media: model.media)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
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
