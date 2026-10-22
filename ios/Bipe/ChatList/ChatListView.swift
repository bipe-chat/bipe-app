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
    @State private var path: [String]
    @State private var showsHandlePick = false
    @State private var creatingGroup = false
    @State private var toast: String?
    @State private var deletingDm: String?
    @ObservedObject private var cleared = ClearedChatsStore.shared
    @Environment(\.scenePhase) private var scenePhase

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String? = nil) {
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
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            creatingGroup = true
                        } label: {
                            PhosphorIcon.plus.image.resizable().frame(width: 22, height: 22)
                        }
                        .accessibilityLabel(BipeStrings.groupsEmptyAction)
                    }
                }
        }
        .environment(\.closeChat, { path.removeAll() })
        .sheet(isPresented: $creatingGroup) {
            GroupCreateView(session: model.session, media: model.media) { roomId, allInvited in
                openChat(roomId)
                if !allInvited {
                    toast = BipeStrings.appGroupCreateInvitesFailed
                }
            }
        }
        .sheet(item: Binding(get: { deletingDm.map(DmSheetId.init) }, set: { deletingDm = $0?.id })) { dm in
            DmDeleteView(session: model.session, roomId: dm.id) {}
        }
        .toast($toast)
        .task { await cleared.refresh(client: model.session.client) }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await cleared.refresh(client: model.session.client) }
            }
        }
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task { await model.start() }
        .task { await QuietGroups.applyOnce(session: model.session) }
        .task { await handle.load() }
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
                // Spec dm-delete: a cleared DM stays hidden until a message after its marker.
                ForEach(model.chats.filter { !cleared.hidden(roomId: $0.roomId, latest: $0.lastActivity) }) { chat in
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

private struct DmSheetId: Identifiable {
    let id: String
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
