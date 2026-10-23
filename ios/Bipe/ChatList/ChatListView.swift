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
    @ObservedObject private var archive: ChatArchiveModel
    @State private var showingArchived = false
    @ObservedObject private var notifications = NotificationActions.shared
    @StateObject private var handle: HandlePickModel
    @StateObject private var requests: ChatRequestsModel
    @StateObject private var banner: RemoteBannerModel
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var encryption: EncryptionStatusModel
    @StateObject private var people: PeopleService
    @State private var path: NavigationPath
    @State private var showsHandlePick = false
    @State private var openingLink: String?
    @State private var newGroupWith: [Person]?
    @State private var notificationsRoom: String?
    @State private var deletingFromProfile: String?
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
        _archive = ObservedObject(wrappedValue: model.archive)
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: model.session))
        let people = PeopleService(session: model.session, api: api, media: model.media)
        _requests = StateObject(wrappedValue: ChatRequestsModel(session: model.session, api: api, people: people))
        _banner = StateObject(wrappedValue: RemoteBannerModel(api: api))
        _encryption = StateObject(wrappedValue: EncryptionStatusModel(session: model.session))
        _people = StateObject(wrappedValue: people)
        _path = State(initialValue: NavigationPath(initialRoomId.map { [$0] } ?? []))
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
        .task { await requests.start() }
        .task { await banner.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await banner.refresh(); await requests.refreshTrust() } }
        }
        .onAppear { people.start() }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
        }
        .sheet(item: Binding(get: { openingLink.map(HandleLink.init) }, set: { openingLink = $0?.handle })) { link in
            ProfileLinkOpenView(people: people, handle: link.handle,
                                openPerson: { path.append(PeopleRoute.person(userId: $0, fromDm: false)) },
                                openOwnLink: { path.append(PeopleRoute.profileLink) })
        }
        // person-profile: group-create with the person picked, chat-notifications and dm-delete for the DM.
        .sheet(item: Binding(get: { newGroupWith.map(PeoplePick.init) }, set: { newGroupWith = $0?.people })) { pick in
            GroupCreateView(session: model.session, media: model.media, people: pick.people) { roomId, _ in
                path = NavigationPath([roomId])
            }
        }
        .sheet(item: Binding(get: { notificationsRoom.map(RoomSheetId.init) }, set: { notificationsRoom = $0?.id })) { room in
            NavigationStack { ChatNotificationsView(session: model.session, roomId: room.id) }
        }
        .sheet(item: Binding(get: { deletingFromProfile.map(RoomSheetId.init) }, set: { deletingFromProfile = $0?.id })) { room in
            DmDeleteView(session: model.session, roomId: room.id) { path = NavigationPath() }
        }
        .onOpenURL { url in
            // Both tabs show a chat list; the Conversas one takes profile links.
            guard scope == .conversas else { return }
            switch PeopleLink(url: url) {
            case .handle(let handle): openingLink = handle
            case .userId(let userId): path.append(PeopleRoute.person(userId: userId, fromDm: false))
            case nil: break
            }
        }
        // Spec notification-service, action open.
        .onReceive(notifications.$roomToOpen.compactMap { $0 }) { roomId in
            notifications.roomToOpen = nil
            path = NavigationPath([roomId])
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
                              routes: PersonProfileRoutes(
                                  openChat: { openChat($0) },
                                  newGroup: { userId in
                                      Task {
                                          let person = try? await people.person(userId)
                                          newGroupWith = [person ?? Person(userId: userId, displayName: Person.localpart(of: userId), avatarUrl: nil)]
                                      }
                                  },
                                  notifications: { notificationsRoom = $0 },
                                  deleteChat: { deletingFromProfile = $0 }))
        case .profileEdit:
            ProfileEditView(people: people)
        case .publicProfile:
            PublicProfileEditView(people: people,
                                  pickHandle: { showsHandlePick = true },
                                  openLink: { path.append(PeopleRoute.profileLink) })
        case .profileQr:
            ProfileLinkView(people: people, pickHandle: { showsHandlePick = true }, initiallyShowsQr: true)
        case .profileLink:
            ProfileLinkView(people: people, pickHandle: { showsHandlePick = true })
        case .contacts:
            ContactsView(people: people, openChat: { openChat($0) }, invite: { showingInvites = true })
        case .whoCanMessage:
            WhoCanMessageView(people: people)
        case .blockedList:
            BlockedListView(people: people) { path.append(PeopleRoute.person(userId: $0, fromDm: false)) }
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
            RemoteBannerView(model: banner)
                .padding(.horizontal, 16)
                .padding(.vertical, banner.state == .hidden ? 0 : 8)
            handlePrompt
            filterBar
            list
        }
    }

    /// The only filter chip: Unread (spec chat-list-filters).
    private var filterBar: some View {
        HStack {
            Button {
                model.toggleUnread(in: scope)
                BipeHaptics.play(.tick)
            } label: {
                Text(BipeStrings.appChatListFilterUnread)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(model.unreadScopes.contains(scope) ? BipeColor.inkOnAccent : BipeColor.inkPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(model.unreadScopes.contains(scope) ? BipeColor.accentFill : BipeColor.surfaceRaised))
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
        Button(showingArchived ? (scope == .conversas ? BipeStrings.appChatListTitle : BipeStrings.tabGroups) : BipeStrings.archivedTitle) {
            showingArchived.toggle()
        }
        .listRowBackground(BipeColor.surfaceBase)
        if archive.failed {
            VStack(alignment: .leading) {
                Text(BipeStrings.archiveError).foregroundStyle(BipeColor.trustDangerInk)
                Button(BipeStrings.appRetry) { archive.retry(model.chats) }
            }
            .listRowBackground(BipeColor.surfaceBase)
        }
        if !showingArchived && scope == .conversas && requests.rowCount > 0 {
            // Action openBox.
            NavigationLink {
                ChatRequestsView(model: requests, media: model.media, onOpenChat: openChat, onOpenWhoCanMessage: { path.append(PeopleRoute.whoCanMessage) })
            } label: {
                ChatRequestsRow(count: requests.rowCount)
            }
            .listRowBackground(BipeColor.surfaceBase)
        }
        ForEach(!showingArchived && scope == .grupos ? requests.visibleGroupInvites : []) { invite in
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
        switch model.phase(in: scope, archived: showingArchived) {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .empty:
            List {
                topRows
                if showingArchived {
                    Text(BipeStrings.archivedEmpty).foregroundStyle(BipeColor.inkSecondary)
                } else { emptyState }
                moreRows
                    .listRowBackground(BipeColor.surfaceBase)
                    .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        case .loaded:
            let chats = model.chats(in: scope, archived: showingArchived)
            List {
                topRows
                ForEach(chats.filter { !cleared.hidden(roomId: $0.roomId, latest: $0.lastActivity) }) { chat in
                    HStack {
                        Button { openChat(chat.roomId) } label: { ChatRow(chat: chat, media: model.media) }
                            .buttonStyle(.plain)
                        Menu { archiveAction(chat) } label: {
                            PhosphorIcon.dotsThree.image.frame(width: 24, height: 24).padding(8)
                        }
                        .accessibilityLabel(BipeStrings.chatActions)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                    .swipeActions(edge: .trailing) { archiveAction(chat) }
                    .contextMenu { archiveAction(chat) }
                    .accessibilityAction(named: archive.contains(chat) ? BipeStrings.unarchiveAction : BipeStrings.archiveAction) {
                        archive.set(chat, archived: !archive.contains(chat))
                    }
                    .onAppear {
                        if chat.id == chats.last?.id {
                            if !showingArchived { model.loadMore(in: scope) }
                        }
                    }
                }
                moreRows
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    private func archiveAction(_ chat: ChatSummary) -> some View {
        Button(archive.contains(chat) ? BipeStrings.unarchiveAction : BipeStrings.archiveAction) {
            archive.set(chat, archived: !archive.contains(chat))
        }
    }

    @ViewBuilder private var moreRows: some View {
        if !showingArchived {
            Button(BipeStrings.chatListMore) { model.loadMore(in: scope) }
                .listRowBackground(BipeColor.surfaceBase)
        }
    }

    /// The empty messages of chat-list-filters: noUnread turns the filter off on a tap; noChats offers start chat.
    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: 12) {
            if model.unreadScopes.contains(scope) {
                Button {
                    model.toggleUnread(in: scope)
                } label: {
                    Text(BipeStrings.appChatListNoUnread)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
                .buttonStyle(.plain)
            } else if scope == .grupos {
                Text(BipeStrings.groupsEmptyTitle).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.groupsEmptyBody).foregroundStyle(BipeColor.inkSecondary)
            } else {
                Text(BipeStrings.chatsEmptyTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.chatsEmptyBody)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.actionStartChat) { path.append(PeopleRoute.contacts) }
                    .buttonStyle(.borderedProminent)
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

private struct PeoplePick: Identifiable {
    let people: [Person]
    var id: String { people.map(\.userId).joined(separator: ",") }
}

private struct RoomSheetId: Identifiable {
    let id: String
}
