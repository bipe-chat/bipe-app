// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Phosphor
import SwiftUI

/// Spec chat-list.
struct ChatListView: View {
    @StateObject private var model: ChatListModel
    @ObservedObject private var notifications = NotificationActions.shared
    @StateObject private var handle: HandlePickModel
    @State private var path: [String]
    @State private var showsHandlePick = false
    private let onOpenSettings: (() -> Void)?

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    /// onOpenSettings: the temporary entry to settings-root until the Você tab (bipe-chat/bipe#567) is on main.
    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String? = nil, onOpenSettings: (() -> Void)? = nil) {
        self.onOpenSettings = onOpenSettings
        _model = StateObject(wrappedValue: ChatListModel(session: session))
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: session))
        _path = State(initialValue: initialRoomId.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.appChatListTitle)
                .navigationDestination(for: String.self) { roomId in
                    ChatTimelineView(session: model.session, media: model.media, roomId: roomId)
                }
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            onOpenSettings?()
                        } label: {
                            PhosphorIcon.gear.image
                                .resizable()
                                .frame(width: 22, height: 22)
                        }
                        .accessibilityLabel(BipeStrings.settingsSection)
                        .opacity(onOpenSettings == nil ? 0 : 1)
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task { await model.start() }
        .task { await handle.load() }
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
        if handle.showsPrompt {
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
        switch model.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            Text(BipeStrings.appChatListEmpty)
                .foregroundStyle(BipeColor.inkSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            List {
                ForEach(model.chats) { chat in
                    Button {
                        openChat(chat.roomId)
                    } label: {
                        ChatRow(chat: chat, media: model.media)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                    .onAppear {
                        if chat.id == model.chats.last?.id {
                            model.loadMore()
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    /// Action openChat.
    private func openChat(_ roomId: String) {
        path.append(roomId)
    }
}

private struct ChatRow: View {
    let chat: ChatSummary
    let media: MediaLoader

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media)
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
