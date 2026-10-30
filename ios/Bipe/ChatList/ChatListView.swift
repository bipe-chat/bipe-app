// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import MatrixRustSDK
import Phosphor
import SwiftUI

private enum RequestInboxRoute: Hashable { case inbox }

/// The root of the detail column: an action from the list replaces it; screens inside the detail push onto `path`.
private enum ChatDetail: Hashable {
    case room(String)
    case requests
    case people(PeopleRoute)
}

/// Spec chat-list, one scope: Conversas or Grupos (ADR 0053), or both in one list for a company account (spec app-tabs).
struct ChatListView: View {
    @ObservedObject private var model: ChatListModel
    @ObservedObject private var archive: ChatArchiveModel
    @State private var showingArchived = false
    @ObservedObject private var notifications = NotificationActions.shared
    @StateObject private var handle: HandlePickModel
    @StateObject private var recovery: RecoveryReminderModel
    @State private var showsRecoveryEmail = false
    // Spec pin-reminder (bipe-chat/bipe#202).
    @StateObject private var pinReminder: PinReminderModel
    @StateObject private var settings: SettingsModel
    @State private var showsPinReminder = false
    @State private var showsPinChange = false
    @StateObject private var requests: ChatRequestsModel
    @StateObject private var banner: RemoteBannerModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    /// Spec encryption-status: one model per account, owned by AppTabsView, which also presents its sheet.
    @ObservedObject private var encryption: EncryptionStatusModel
    @StateObject private var people: PeopleService
    /// One selection for both layouts. A wide window shows it beside the list; a narrow one pushes it.
    /// A change of window size keeps the same detail, timeline and path.
    @State private var detail: ChatDetail?
    @State private var path = NavigationPath()
    @State private var compactColumn: NavigationSplitViewColumn
    @State private var showsHandlePick = false
    @State private var openingLink: String?
    /// queue-customer from the counter QR code (https://bipe.chat/@handle?fila).
    @State private var openingQueue: String?
    /// A group link waiting for room-link-open.
    @State private var openingRoomLink: RoomLink?
    /// A pack link (spec sticker-pack).
    @State private var openingPack: StickerPackRef?
    @State private var newGroupWith: [Person]?
    @State private var notificationsRoom: String?
    @State private var groupDetailsRoom: String?
    @State private var deletingFromProfile: String?
    private let scope: ChatScope
    private let api: BipeAPIClient
    @State private var showingInvites = false
    @State private var creatingGroup = false
    @State private var deletingDm: String?
    @ObservedObject private var cleared = ClearedChatsStore.shared
    /// Spec drafts openList: the list of the assistant drafts, from the chat list toolbar while drafts exist.
    @ObservedObject private var assistantDrafts = AssistantDraftsStore.shared
    @State private var showsDrafts = false
    @StateObject private var cards: FirstListCardsModel
    @State private var shareItem: ShareItem?
    private struct ShareItem: Identifiable {
        let url: URL
        var id: URL { url }
    }

    /// initialRoomId: the chat to open at once (signup-done opens the chat with the inviter).
    init(model: ChatListModel, scope: ChatScope, api: BipeAPIClient, encryption: EncryptionStatusModel,
         initialRoomId: String? = nil) {
        self.api = api
        _model = ObservedObject(wrappedValue: model)
        _archive = ObservedObject(wrappedValue: model.archive)
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: model.session))
        _recovery = StateObject(wrappedValue: RecoveryReminderModel(api: api, session: model.session))
        _pinReminder = StateObject(wrappedValue: PinReminderModel(session: model.session))
        _settings = StateObject(wrappedValue: SettingsModel(session: model.session, api: api))
        let people = PeopleService(session: model.session, api: api, media: model.media)
        _requests = StateObject(wrappedValue: ChatRequestsModel(session: model.session, api: api, people: people))
        _banner = StateObject(wrappedValue: RemoteBannerModel(api: api))
        _encryption = ObservedObject(wrappedValue: encryption)
        _people = StateObject(wrappedValue: people)
        _detail = State(initialValue: initialRoomId.map(ChatDetail.room))
        _compactColumn = State(initialValue: initialRoomId == nil ? .sidebar : .detail)
        _cards = StateObject(wrappedValue: FirstListCardsModel(userId: model.session.userId))
        self.scope = scope
    }

    var body: some View {
        NavigationSplitView(preferredCompactColumn: $compactColumn) {
            content
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(scope == .grupos ? BipeStrings.tabGroups : BipeStrings.appChatListTitle)
                .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 420)
                .toolbar {
                    // No account avatar here: Você shows the current account (MZ decision 25 Oct 2026, spec chat-list).
                    ToolbarItem(placement: .topBarTrailing) {
                        CreateGroupButton(createGroup: { creatingGroup = true }, invite: { showingInvites = true })
                    }
                    if assistantDrafts.userId == model.session.userId, !assistantDrafts.drafts.isEmpty {
                        ToolbarItem(placement: .topBarLeading) {
                            Button(BipeStrings.appDraftsTitle) { showsDrafts = true }
                        }
                    }
                }
        } detail: {
            NavigationStack(path: $path) {
                detailRoot
                    .navigationDestination(for: String.self) { roomId in
                        timeline(roomId)
                    }
                    .navigationDestination(for: RequestInboxRoute.self) { _ in
                        requestsScreen
                    }
                    .navigationDestination(for: PeopleRoute.self) { route in
                        peopleScreen(route)
                    }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .onChange(of: compactColumn) { _, column in
            // Back from the detail on a narrow window: nothing stays selected underneath.
            if column == .sidebar && horizontalSizeClass == .compact { closeDetail() }
        }
        .groupHost(session: model.session, api: api, media: model.media, openChat: openChat, closeChat: closeDetail,
                   creatingGroup: $creatingGroup, deletingDm: $deletingDm, showingInvites: $showingInvites)
        .accessibilityIdentifier(BipeScreen.chatList.rawValue)
        .task {
            if scope != .grupos {
                await handle.load()
                await recovery.load()
            }
        }
        .task { await requests.start() }
        .onReceive(model.$chats) { requests.rememberDmPartners($0) }
        .onReceive(model.$hasUndecryptable) { if $0 { encryption.noteUndecryptable() } }
        .task { await banner.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await banner.refresh()
                    await requests.refreshTrust()
                    if scope != .grupos { await recovery.load() }
                    pinReminder.refresh()
                }
            }
        }
        .environment(\.bipeBrandMarks, people.brandMarks)
        .onAppear { people.start() }
        .sheet(isPresented: $showsDrafts) {
            AssistantDraftsList(store: assistantDrafts, userId: model.session.userId) { openChat($0) }
        }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
        }
        .sheet(item: $shareItem) { item in
            ShareSheet(items: [item.url]).ignoresSafeArea()
        }
        .sheet(isPresented: $showsPinReminder) {
            PinReminderView(session: model.session, api: api, onResult: { pinReminder.checked($0) },
                            changePin: { Task { try? await Task.sleep(for: .milliseconds(500)); showsPinChange = true } })
        }
        .sheet(isPresented: $showsPinChange) {
            NavigationStack { PinChangeView(model: settings) }
        }
        .sheet(isPresented: $showsRecoveryEmail) {
            RecoveryEmailAddView(api: api, session: model.session, current: recovery.account?.email,
                                 pending: recovery.account?.emailPending) {
                Task { await recovery.load() }
            }
        }
        .sheet(item: Binding(get: { openingLink.map(HandleLink.init) }, set: { openingLink = $0?.handle })) { link in
            ProfileLinkOpenView(people: people, handle: link.handle,
                                openPerson: { show(.people(.person(userId: $0, fromDm: false))) },
                                openOwnLink: { show(.people(.profileLink)) })
        }
        .sheet(item: $openingRoomLink) { link in
            RoomLinkOpenView(session: model.session, link: link, media: model.media) { openChat($0) }
        }
        .sheet(item: $openingPack) { pack in
            StickerPackView(model: StickerPackModel(ref: pack, client: model.session.client, handles: people.handles),
                            loader: model.media) { openingPack = nil }
        }
        .sheet(item: Binding(get: { openingQueue.map(HandleLink.init) }, set: { openingQueue = $0?.handle })) { link in
            QueueCustomerView(api: model.session.business(api), business: link.handle, close: { _ in openingQueue = nil })
        }
        // person-profile: group-create with the person picked, chat-notifications and dm-delete for the DM.
        .sheet(item: Binding(get: { newGroupWith.map(PeoplePick.init) }, set: { newGroupWith = $0?.people })) { pick in
            GroupCreateView(session: model.session, media: model.media, people: pick.people) { roomId, _ in
                openChat(roomId)
            }
        }
        .sheet(item: Binding(get: { notificationsRoom.map(RoomSheetId.init) }, set: { notificationsRoom = $0?.id })) { room in
            NavigationStack { ChatNotificationsView(session: model.session, roomId: room.id) }
        }
        .sheet(item: Binding(get: { groupDetailsRoom.map(RoomSheetId.init) }, set: { groupDetailsRoom = $0?.id })) { room in
            NavigationStack {
                GroupDetailsView(session: model.session, media: model.media, roomId: room.id)
                    .id(model.session.userId)
                    .toolbar { ToolbarItem(placement: .confirmationAction) { Button(BipeStrings.appClose) { groupDetailsRoom = nil } } }
            }
        }
        .sheet(item: Binding(get: { deletingFromProfile.map(RoomSheetId.init) }, set: { deletingFromProfile = $0?.id })) { room in
            DmDeleteView(session: model.session, roomId: room.id) { closeDetail() }
        }
        // invite-signed-in: the inviter's chat or profile-link-open, in the Conversas list.
        .onReceive(TabsLinks.shared.$link.compactMap { $0 }) { link in
            guard scope != .grupos else { return }
            TabsLinks.shared.link = nil
            switch link {
            case .chat(let roomId): openChat(roomId)
            case .profile(let handle): openingLink = handle
            case .person(let userId): show(.people(.person(userId: userId, fromDm: false)))
            case .queue(let handle): openingQueue = handle
            case .room(let link): openingRoomLink = link
            }
        }
        .onOpenURL { url in
            // Both tabs show a chat list; the Conversas one takes profile links.
            guard scope != .grupos else { return }
            if let pack = StickerPackRef.fromLink(url.absoluteString) {
                openingPack = pack
                return
            }
            switch PeopleLink(url: url) {
            case .handle(let handle): openingLink = handle
            case .userId(let userId): show(.people(.person(userId: userId, fromDm: false)))
            case .queue(let handle): openingQueue = handle
            case nil: openingRoomLink = RoomLink(url: url)
            }
        }
        // Spec notification-service, action open.
        .onReceive(notifications.$roomToOpen.compactMap { $0 }) { target in
            // @Published emits before assignment. Consume after the owner/tab route has settled.
            Task { @MainActor in
                let destinationScope: ChatScope = target.destination == .groups ? .grupos : .conversas
                guard scope == destinationScope || scope == .todas, target.accountUserId == model.session.userId,
                      notifications.owns(model.session), notifications.roomToOpen == target else { return }
                var opensChat = false
                if target.destination == .chat,
                   let room = try? model.session.client.getRoom(roomId: target.roomId),
                   let info = try? await room.roomInfo() {
                    opensChat = info.membership == .joined && !info.isSpace
                }
                guard notifications.owns(model.session), notifications.roomToOpen == target else { return }
                notifications.roomToOpen = nil
                showingArchived = false
                switch target.destination {
                case .chat: if opensChat { openChat(target.roomId) } else { closeDetail() }
                case .requests: show(.requests)
                case .groups, .inbox: closeDetail()
                }
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
            PhosphorIcon.userCircle.labelledImage(BipeStrings.settingsPublicProfile)
                .resizable()
                .frame(width: 26, height: 26)
                .foregroundStyle(BipeColor.accentInk)
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

    /// One card at a time: the handle card first, then the recovery reminder (spec recovery-reminder), then the PIN
    /// reminder (spec pin-reminder).
    @ViewBuilder
    private var handlePrompt: some View {
        if scope != .grupos && handle.showsPrompt {
            HandlePromptCard(model: handle) { showsHandlePick = true }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
        } else if scope != .grupos && recovery.showsCard {
            RecoveryReminderCard(model: recovery) { showsRecoveryEmail = true }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
        } else if scope != .grupos && pinReminder.showsCard {
            PinReminderCard(model: pinReminder) { showsPinReminder = true }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            // Spec live-location-banner: this account shares its live location.
            LiveLocationListRow(userId: model.session.userId)
            RemoteBannerView(model: banner)
                .padding(.horizontal, 16)
                .padding(.vertical, banner.state == .hidden ? 0 : 8)
            handlePrompt
            list
        }
    }

    /// Spec chat-list-cards: the first-list cards at the top of Conversas.
    @ViewBuilder
    private var firstListCards: some View {
        if scope != .grupos && !showingArchived {
            let count = model.chats(in: .conversas, archived: false).count
            ForEach(cards.visible(chatCount: count, handle: handle.currentHandle), id: \.self) { card in
                FirstListCardView(card: card, handle: handle.currentHandle) { target in run(target, of: card) }
                    .listRowBackground(BipeColor.surfaceBase)
                    .listRowSeparator(.hidden)
            }
        }
    }

    /// The card actions of spec chat-list-cards. Each one, dismiss included, ends the card.
    private func run(_ target: BipeOnboardingTarget, of card: BipeOnboardingCard) {
        cards.finish(card)
        switch target {
        case .shareInvite:
            let session = model.session
            Task {
                let creator = BipeInviteCreator(apiClient: api, userID: session.userId, accessToken: { session.accessToken() })
                if case .created(let invite)? = try? await creator.createIfAvailable() {
                    shareItem = ShareItem(url: invite.url)
                } else {
                    showingInvites = true
                }
            }
        case .shareProfile:
            if let url = handle.currentHandle.flatMap(BipeProfileLink.url(handle:)) { shareItem = ShareItem(url: url) }
        case .profileQr:
            show(.people(.profileQr))
        case .notes:
            Task { if let roomId = try? await model.openNotes() { openChat(roomId) } }
        default:
            break
        }
    }

    /// The requests row and the group invites above the chats (specs chat-requests and chat-list-filters).
    @ViewBuilder
    private var topRows: some View {
        // Spec encryption-status: the banner is the first row, so it scrolls with the chats (MZ decision 25 Oct 2026),
        // in every chat list (bipe-chat/bipe#853).
        if encryption.showsBanner && !showingArchived {
            HistoryBanner(model: encryption)
                .listRowInsets(EdgeInsets())
                .listRowBackground(BipeColor.surfaceRaised)
        }
        firstListCards
        Button(showingArchived ? (scope == .grupos ? BipeStrings.tabGroups : BipeStrings.appChatListTitle) : BipeStrings.archivedTitle) {
            showingArchived.toggle()
        }
        if model.readActionFailed {
            VStack(alignment: .leading) {
                Text(BipeStrings.chatReadError).foregroundStyle(BipeColor.trustDangerInk)
                Button(BipeStrings.appRetry) { Task { await model.retryReadAction() } }
                    .disabled(model.markingRoomId != nil)
            }
        }
        if model.favouriteFailed {
            Text(BipeStrings.errorGeneric).foregroundStyle(BipeColor.trustDangerInk)
        }
        if archive.failed {
            VStack(alignment: .leading) {
                Text(BipeStrings.archiveError).foregroundStyle(BipeColor.trustDangerInk)
                Button(BipeStrings.appRetry) { archive.retry(model.chats) }
            }
        }
        if !showingArchived && scope != .grupos && requests.rowCount > 0 {
            // Action openBox.
            Button { show(.requests) } label: {
                ChatRequestsRow(count: requests.rowCount)
            }
            .listRowBackground(rowBackground(.requests))
        }
        ForEach(!showingArchived && scope != .conversas ? requests.visibleGroupInvites : []) { invite in
            GroupInviteRow(model: requests, invite: invite, media: model.media, onOpenChat: openChat)
        }
        if requests.error {
            Text(BipeStrings.errorGeneric)
                .foregroundStyle(BipeColor.trustDangerInk)
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
                    .listRowSeparator(.hidden)
            }
            .listStyle(.plain)
        case .loaded:
            // Spec chat-list favourites: a favourite shows in the row at the top, not again below it.
            let chats = model.chats(in: scope, archived: showingArchived).filter { showingArchived || !$0.isFavourite }
            let favourites = showingArchived ? [] : model.favourites(in: scope)
                .filter { !cleared.hidden(roomId: $0.roomId, latest: $0.lastActivity) }
            List {
                // The first item of the list, above banners, cards, Archived, requests and invites.
                if !favourites.isEmpty {
                    FavouritesGrid(chats: favourites, media: model.media, open: openChat) { rowMenu($0) }
                        .listRowBackground(BipeColor.surfaceBase)
                        .listRowSeparator(.hidden)
                }
                topRows
                ForEach(chats.filter { !cleared.hidden(roomId: $0.roomId, latest: $0.lastActivity) }) { chat in
                    Button { openChat(chat.roomId) } label: { ChatRow(chat: chat, media: model.media) }
                    .buttonStyle(.plain)
                    .listRowBackground(rowBackground(.room(chat.roomId)))
                    .swipeActions(edge: .trailing) { archiveAction(chat) }
                    .contextMenu { rowMenu(chat.roomId) }
                    .accessibilityActions { rowMenu(chat.roomId) }
                    .onAppear {
                        if chat.id == chats.last?.id {
                            if !showingArchived { model.loadMore(in: scope) }
                        }
                    }
                }
                moreRows
            }
            .listStyle(.plain)
        }
    }

    @ViewBuilder private func rowMenu(_ roomId: String) -> some View {
        if let chat = model.currentChat(roomId) {
            archiveAction(chat)
            // Spec chat-row-actions favourite. The SDK drops m.lowpriority when it sets m.favourite: not for an archive.
            if !archive.contains(chat) {
                Button {
                    Task { await model.setFavourite(roomId: roomId, favourite: !chat.isFavourite) }
                } label: {
                    Label(chat.isFavourite ? BipeStrings.chatUnfavouriteAction : BipeStrings.chatFavouriteAction,
                          systemImage: chat.isFavourite ? "star.slash" : "star")
                }
            }
            Button {
                Task { await model.setUnread(roomId: roomId, unread: !chat.isUnread) }
            } label: {
                Label(chat.isUnread ? BipeStrings.appNotificationMarkRead : BipeStrings.chatMarkUnread,
                      systemImage: chat.isUnread ? "envelope.open" : "envelope.badge")
            }
            .disabled(model.markingRoomId != nil)
            Button { notificationsRoom = roomId } label: {
                Label(BipeStrings.settingsNotifications, systemImage: "bell")
            }
            Button { openDetails(chat) } label: {
                Label(BipeStrings.chatDetails, systemImage: "info.circle")
            }
                .disabled(chat.isDm && chat.peerUserId == nil)
            if chat.isDm {
                Button(role: .destructive) { deletingDm = roomId } label: {
                    Label(BipeStrings.dmDeleteAction, systemImage: "trash")
                }
            }
        }
    }

    private func openDetails(_ chat: ChatSummary) {
        if chat.isDm {
            if let peer = chat.peerUserId { show(.people(.person(userId: peer, fromDm: true))) }
        } else { groupDetailsRoom = chat.roomId }
    }

    private func archiveAction(_ chat: ChatSummary) -> some View {
        let archived = archive.contains(chat)
        return Button {
            archive.set(chat, archived: !archived)
        } label: {
            Label(archived ? BipeStrings.unarchiveAction : BipeStrings.archiveAction,
                  systemImage: archived ? "tray.and.arrow.up" : "archivebox")
        }
    }

    @ViewBuilder private var moreRows: some View {
        if !showingArchived {
            Button(BipeStrings.chatListMore) { model.loadMore(in: scope) }
        }
    }

    /// The empty messages of chat-list-filters: noChats offers start chat.
    @ViewBuilder
    private var emptyState: some View {
        VStack(spacing: 12) {
            if scope == .grupos {
                Text(BipeStrings.groupsEmptyTitle).font(.headline).foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.groupsEmptyBody).foregroundStyle(BipeColor.inkSecondary)
                // Spec chat-list-cards createGroup.
                Button(BipeStrings.groupsEmptyCreate) { creatingGroup = true }
                    .buttonStyle(.bipeProminent)
            } else {
                Text(BipeStrings.chatsEmptyTitle)
                    .font(.headline)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(BipeStrings.chatsEmptyBody)
                    .foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.actionStartChat) { show(.people(.contacts)) }
                    .buttonStyle(.bipeProminent)
            }
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48)
    }

    /// Action openChat. It replaces the detail, from the list or from a screen inside the detail.
    private func openChat(_ roomId: String) {
        show(.room(roomId))
    }

    private func show(_ next: ChatDetail) {
        detail = next
        path = NavigationPath()
        compactColumn = .detail
    }

    /// closeChat: back to the list with nothing selected.
    private func closeDetail() {
        detail = nil
        path = NavigationPath()
        compactColumn = .sidebar
    }

    /// The selected row stays marked while its detail is beside the list.
    /// nil keeps the standard list row background.
    private func rowBackground(_ row: ChatDetail) -> Color? {
        horizontalSizeClass == .regular && detail == row ? BipeColor.accentWash : nil
    }

    @ViewBuilder
    private var detailRoot: some View {
        switch detail {
        case .room(let roomId):
            // The room id keeps one timeline per chat; the layout change does not replace it.
            timeline(roomId).id(roomId)
        case .requests:
            requestsScreen
        case .people(let route):
            peopleScreen(route)
        case nil:
            // Wide window with no chat open. The list beside it is the call to action, so no text here.
            BipeMark()
                .fill(BipeColor.inkSecondary)
                .frame(width: 64, height: 64)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .accessibilityHidden(true)
        }
    }

    private func timeline(_ roomId: String) -> some View {
        ChatTimelineView(session: model.session, media: model.media, roomId: roomId, people: people) {
            Task {
                if let userId = await people.dmPartner(roomId: roomId) {
                    path.append(PeopleRoute.person(userId: userId, fromDm: true))
                }
            }
        }
    }

    private var requestsScreen: some View {
        ChatRequestsView(model: requests, media: model.media, onOpenChat: openChat,
                         onOpenWhoCanMessage: { path.append(PeopleRoute.whoCanMessage) })
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
                    if chat.isOfficial {
                        OfficialBadge()
                    }
                    Spacer(minLength: 8)
                    if let lastActivity = chat.lastActivity {
                        Text(lastActivity, format: Self.timeFormat(for: lastActivity))
                            .font(.caption)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .monospacedDigit()
                            .fixedSize(horizontal: true, vertical: false)
                            .layoutPriority(1)
                            .accessibilityLabel(lastActivity.formatted(date: .complete, time: .shortened))
                    }
                }
                // Spec brand-mark: a reseller's mark under the name.
                BrandMarkCaption(userId: chat.peerUserId)
                HStack {
                    Text(chat.lastMessage ?? "")
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(2)
                    Spacer(minLength: 8)
                    // Spec chat-notifications: a muted chat keeps its badge, without sound or notification.
                    if chat.isMuted {
                        PhosphorIcon.bellSlash.labelledImage(BipeStrings.appNotificationModeOff)
                            .resizable()
                            .frame(width: 16, height: 16)
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    if chat.unreadCount > 0 {
                        if chat.mentionCount > 0 {
                            // A mention has its own mark, not only a colour (bipe#497). The count's label reads it.
                            Text(verbatim: "@")
                                .font(.caption.bold())
                                .foregroundStyle(BipeColor.inkOnAccent)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Circle().fill(BipeColor.accentFill))
                                .accessibilityHidden(true)
                        }
                        let count = chat.unreadCount.formatted()
                        Text(count)
                            .font(.caption.bold())
                            .foregroundStyle(BipeColor.inkOnAccent)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(Capsule().fill(BipeColor.accentFill))
                            // A mention is read with the count; the badge shows it by colour only (bipe#495).
                            .accessibilityLabel(chat.mentionCount > 0
                                ? BipeStrings.appChatListUnread(count) + ", " + BipeStrings.a11yMention
                                : BipeStrings.appChatListUnread(count))
                    } else if chat.isMarkedUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8)
                            .accessibilityLabel(BipeStrings.chatUnreadState)
                    }
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    /// The chat-list date: the time today, else day and month. chat-search message hits use it too.
    static func timeFormat(for date: Date) -> Date.FormatStyle {
        Calendar.current.isDateInToday(date)
            ? .dateTime.hour().minute()
            : .dateTime.day().month(.abbreviated)
    }
}

/// Spec chat-list favourites: large avatars, three per line, the name under each.
private struct FavouritesGrid<MenuContent: View>: View {
    let chats: [ChatSummary]
    let media: MediaLoader
    let open: (String) -> Void
    @ViewBuilder let menu: (String) -> MenuContent
    @ScaledMetric(relativeTo: .caption) private var avatarSize: CGFloat = 72

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 16) {
            ForEach(chats) { chat in
                Button { open(chat.roomId) } label: { cell(chat) }
                    .buttonStyle(.plain)
                    .contextMenu { menu(chat.roomId) }
                    .accessibilityActions { menu(chat.roomId) }
                    .accessibilityLabel(chat.isUnread ? chat.name + ", " + BipeStrings.chatUnreadState : chat.name)
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(BipeStrings.chatFavourites)
    }

    private func cell(_ chat: ChatSummary) -> some View {
        VStack(spacing: 6) {
            AvatarView(name: chat.name, mxcUrl: chat.avatarUrl, loader: media, size: avatarSize, userId: chat.peerUserId)
                .overlay(alignment: .topTrailing) {
                    if chat.isUnread {
                        Circle().fill(BipeColor.accentFill)
                            .frame(width: avatarSize * 0.2, height: avatarSize * 0.2)
                            .overlay(Circle().stroke(BipeColor.surfaceBase, lineWidth: 2))
                    }
                }
            Text(chat.name)
                .font(.caption)
                .foregroundStyle(BipeColor.inkPrimary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
    }
}

private struct PeoplePick: Identifiable {
    let people: [Person]
    var id: String { people.map(\.userId).joined(separator: ",") }
}

private struct RoomSheetId: Identifiable {
    let id: String
}
