// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec chat-list.
struct ChatListView: View {
    @StateObject private var model: ChatListModel
    @StateObject private var handle: HandlePickModel
    @StateObject private var requests: ChatRequestsModel
    @StateObject private var search: ChatSearchModel
    @StateObject private var banner: RemoteBannerModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var path: [String]
    @State private var showsHandlePick = false
    private let onOpenSettings: (() -> Void)?

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    /// onOpenSettings: the temporary entry to settings-root until the Você tab (bipe-chat/bipe#567) is on main.
    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String? = nil, onOpenSettings: (() -> Void)? = nil) {
        self.onOpenSettings = onOpenSettings
        _model = StateObject(wrappedValue: ChatListModel(session: session))
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: session))
        _requests = StateObject(wrappedValue: ChatRequestsModel(session: session))
        _search = StateObject(wrappedValue: ChatSearchModel(session: session))
        _banner = StateObject(wrappedValue: RemoteBannerModel(api: api))
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
        .task { await requests.start() }
        // remote-banner, action refresh: when the list appears and when the app comes to the foreground.
        .task { await banner.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await banner.refresh() } }
        }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
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
            RemoteBannerView(model: banner)
                .padding(.horizontal, 16)
                .padding(.vertical, banner.state == .hidden ? 0 : 8)
            handlePrompt
            if search.query.isEmpty {
                filterBar
                list
            } else {
                ChatSearchResults(search: search, media: model.media) { roomId in
                    search.close()
                    openChat(roomId)
                }
            }
        }
        // chat-search: the top of the list until the search tab of the tab bar (ADR 0053) is on main.
        .searchable(text: Binding(get: { search.query }, set: { search.edit($0) }), prompt: Text(BipeStrings.appSearchPlaceholder))
    }

    /// The only filter chip: Unread (spec chat-list-filters).
    private var filterBar: some View {
        HStack {
            Button {
                model.toggleUnread()
                BipeHaptics.play(.tick)
            } label: {
                Text(BipeStrings.appChatListFilterUnread)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(model.unreadOnly ? BipeColor.inkOnAccent : BipeColor.inkPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(model.unreadOnly ? BipeColor.accentFill : BipeColor.surfaceRaised))
            }
            .buttonStyle(.plain)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    /// The requests row and the group invites above the chats (specs chat-requests and chat-list-filters).
    @ViewBuilder
    private var topRows: some View {
        if requests.rowCount > 0 {
            // Action openBox.
            NavigationLink {
                ChatRequestsView(model: requests, media: model.media, onOpenChat: openChat, onOpenWhoCanMessage: { onOpenSettings?() })
            } label: {
                ChatRequestsRow(count: requests.rowCount)
            }
            .listRowBackground(BipeColor.surfaceBase)
        }
        ForEach(requests.visibleGroupInvites) { invite in
            GroupInviteRow(model: requests, invite: invite, media: model.media, onOpenChat: openChat)
                .listRowBackground(BipeColor.surfaceBase)
        }
        if requests.error {
            Text(BipeStrings.errorGeneric)
                .foregroundStyle(BipeColor.trustDangerInk)
                .listRowBackground(BipeColor.surfaceBase)
        }
    }

    @ViewBuilder
    private var list: some View {
        switch model.phase {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            List {
                topRows
                emptyState
                    .listRowBackground(BipeColor.surfaceBase)
                    .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        case .loaded:
            List {
                topRows
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

    /// The empty messages of chat-list-filters: noUnread turns the filter off on a tap; noChats offers start chat.
    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: 12) {
            if model.unreadOnly {
                Button {
                    model.toggleUnread()
                } label: {
                    Text(BipeStrings.appChatListNoUnread)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                .buttonStyle(.plain)
            } else {
                Text(BipeStrings.chatsEmptyTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.chatsEmptyBody)
                    .foregroundStyle(BipeColor.inkSecondary)
                // Action startChat: the start-chat screen comes with people and profiles (bipe-chat/bipe#568).
                Text(BipeStrings.actionStartChat)
                    .foregroundStyle(BipeColor.inkDisabled)
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
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
