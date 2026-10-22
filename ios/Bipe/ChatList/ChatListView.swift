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
    @StateObject private var people: PeopleService
    @State private var path: NavigationPath
    @State private var showsHandlePick = false
    @State private var openingLink: String?

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    init(session: AccountSession, api: BipeAPIClient, initialRoomId: String? = nil) {
        let model = ChatListModel(session: session)
        _model = StateObject(wrappedValue: model)
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: session))
        _people = StateObject(wrappedValue: PeopleService(session: session, api: api, media: model.media))
        _path = State(initialValue: NavigationPath(initialRoomId.map { [$0] } ?? []))
    }

    var body: some View {
        NavigationStack(path: $path) {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.appChatListTitle)
                .navigationDestination(for: String.self) { roomId in
                    ChatTimelineView(session: model.session, media: model.media, roomId: roomId) {
                        Task {
                            if let userId = await people.dmPartner(roomId: roomId) {
                                path.append(PeopleRoute.person(userId: userId, fromDm: true))
                            }
                        }
                    }
                }
                .navigationDestination(for: PeopleRoute.self) { route in
                    peopleScreen(route)
                }
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        peopleMenu
                    }
                }
        }
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task { await model.start() }
        .task { await handle.load() }
        .onAppear { people.start() }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
        }
        .sheet(item: Binding(get: { openingLink.map(HandleLink.init) }, set: { openingLink = $0?.handle })) { link in
            ProfileLinkOpenView(people: people, handle: link.handle,
                                openPerson: { path.append(PeopleRoute.person(userId: $0, fromDm: false)) },
                                openOwnLink: { path.append(PeopleRoute.profileLink) })
        }
        .onOpenURL { url in
            switch PeopleLink(url: url) {
            case .handle(let handle): openingLink = handle
            case .userId(let userId): path.append(PeopleRoute.person(userId: userId, fromDm: false))
            case nil: break
            }
        }
    }

    /// The people and profile screens (bipe-chat/bipe#568). Until the Você tab and Privacidade exist
    /// (lanes D and E), this menu is their way in.
    private var peopleMenu: some View {
        Menu {
            Button { path.append(PeopleRoute.profileEdit) } label: { IconLabel(icon: .userCircle, text: BipeStrings.settingsProfileNamePhoto) }
            Button { path.append(PeopleRoute.publicProfile) } label: { IconLabel(icon: .userCircle, text: BipeStrings.settingsPublicProfile) }
            Button { path.append(PeopleRoute.profileLink) } label: { IconLabel(icon: .qrCode, text: BipeStrings.appProfileLink) }
            Button { path.append(PeopleRoute.contacts) } label: { IconLabel(icon: .addressBook, text: BipeStrings.contactsTitle) }
            Button { path.append(PeopleRoute.whoCanMessage) } label: { IconLabel(icon: .chatText, text: BipeStrings.messagingTitle) }
            Button { path.append(PeopleRoute.blockedList) } label: { IconLabel(icon: .prohibit, text: BipeStrings.privacyBlocked) }
        } label: {
            PhosphorIcon.userCircle.image
                .resizable()
                .frame(width: 26, height: 26)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityLabel(BipeStrings.settingsPublicProfile)
        }
    }

    @ViewBuilder
    private func peopleScreen(_ route: PeopleRoute) -> some View {
        switch route {
        case .person(let userId, let fromDm):
            PersonProfileView(people: people, userId: userId, fromDm: fromDm,
                              routes: PersonProfileRoutes(openChat: { openChat($0) }))
        case .profileEdit:
            ProfileEditView(people: people)
        case .publicProfile:
            PublicProfileEditView(people: people,
                                  pickHandle: { showsHandlePick = true },
                                  openLink: { path.append(PeopleRoute.profileLink) })
        case .profileLink:
            ProfileLinkView(people: people, pickHandle: { showsHandlePick = true })
        case .contacts:
            ContactsView(people: people, openChat: { openChat($0) }, invite: nil)
        case .whoCanMessage:
            WhoCanMessageView(people: people)
        case .blockedList:
            BlockedListView(people: people) { path.append(PeopleRoute.person(userId: $0, fromDm: false)) }
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
