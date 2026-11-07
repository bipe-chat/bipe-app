// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import Phosphor
import SwiftUI
import UIKit

/// Spec chat-timeline, with the rows of timeline-rows and the interactions of the message specs.
struct ChatTimelineView: View {
    @StateObject private var model: ChatTimelineModel
    @Environment(\.scenePhase) private var scenePhase
    /// The account's call focus, for the call rows of the DM chat menu.
    @State private var callFocus: String?
    /// The DM chat menu's chat-notifications and chat-media-gallery rows.
    @State private var showsNotifications = false
    @State private var showsGallery = false
    @State private var toast: String?
    /// The list keeps to the newest message until the person scrolls up (chat-timeline opens at the bottom).
    @State private var followsNewest = true
    @State private var scrollPhase: ScrollPhase = .idle
    /// Spec chat-timeline searchInChat (bipe-chat/bipe#861).
    @State private var searchingChat = false
    /// The group menu's group-details row.
    @State private var showsGroupDetails = false
    /// Spec group-details notes, Notes: the group menu names the info screen as a chat, not a group.
    @State private var isNotes = false
    @ObservedObject private var cleared = ClearedChatsStore.shared
    /// Spec drafts (bipe-chat/bipe#866): the assistant draft banner above the composer.
    @ObservedObject private var assistantDrafts = AssistantDraftsStore.shared
    @Environment(\.closeChat) private var closeChat
    @Environment(\.openOtherChat) private var openOtherChat
    @State private var creatingPoll = false
    @State private var openPollId: String?
    @StateObject private var voicePlayer = VoicePlayer()
    /// Spec business-chat-bar: the tools and booking cards of a DM with a company, or of its customer.
    @StateObject private var business: BusinessChatModel
    /// Spec message-pins (bipe#665).
    @StateObject private var pinned: PinnedMessagesModel
    /// Spec identity-change (bipe#770).
    @StateObject private var identity: IdentityChangeModel
    @State private var viewerRowId: String?
    // composer
    @State private var attaching: AttachmentChoice?
    @State private var upload: PickedUpload?
    @State private var showsStickers = false
    /// Spec location-send, with the reply target taken when it opened.
    @State private var locationSend: LocationSendRequest?
    /// Spec chat-timeline floatingDate (bipe-chat/bipe#828): the pill shows while the list scrolls.
    @State private var showsFloatingDate = false
    @State private var floatingDateHide: Task<Void, Never>?
    /// The row IDs on screen, for the day of the top one.
    @State private var visibleRowIds: [String] = []
    /// Spec chat-timeline jumpToDate: the date picker is open.
    @State private var pickingDate = false
    private let media: MediaLoader
    /// Opens the chat's info: person-profile for a DM.
    private let openInfo: (() -> Void)?
    /// Spec chat-archive from the DM chat menu. nil: no archive row.
    private let archive: ChatArchiveToggle?
    private let session: AccountSession
    private let roomId: String
    /// Spec chat-search openMessage: the message to scroll to after the chat opens.
    private let focusEventId: String?
    /// Spec chat-row-actions sendMedia: the attachment choice to open once the chat shows.
    private let startAttachment: AttachmentChoice?
    /// The account's people service, for the blocked bar of a DM (block-person). nil: no bar.
    private let people: PeopleService?

    /// threadRoot: the thread view of spec threads (bipe#667); nil for the main timeline.
    /// day: the day view of spec chat-timeline dayFocus (bipe#828); nil for the main timeline.
    init(session: AccountSession, media: MediaLoader, roomId: String, people: PeopleService? = nil,
         focusEventId: String? = nil, threadRoot: String? = nil, day: DayFocus? = nil, openInfo: (() -> Void)? = nil,
         archive: ChatArchiveToggle? = nil, startAttachment: AttachmentChoice? = nil) {
        self.focusEventId = focusEventId
        self.startAttachment = startAttachment
        _model = StateObject(wrappedValue: ChatTimelineModel(session: session, roomId: roomId, threadRoot: threadRoot, dayFocus: day))
        _business = StateObject(wrappedValue: BusinessChatModel(session: session, media: media))
        _pinned = StateObject(wrappedValue: PinnedMessagesModel(session: session, roomId: roomId))
        _identity = StateObject(wrappedValue: IdentityChangeModel(session: session, roomId: roomId))
        self.media = media
        self.openInfo = openInfo
        self.archive = archive
        self.session = session
        self.roomId = roomId
        self.people = people
    }

    var body: some View {
        VStack(spacing: 0) {
            // Spec threads notes: the thread view shows none of the chat bars; nor does the day view (dayFocus).
            if model.isMainTimeline {
                OngoingCallBar(model: model, session: session)
                if let predecessor = model.predecessor {
                    PredecessorLine(name: predecessor.name) { openOtherChat(predecessor.roomId) }
                }
                if model.isGroup {
                    PinnedBanner(model: pinned) { eventId in Task { await model.openOriginal(eventId: eventId) } }
                }
                LiveLocationChatBar(roomId: roomId, userId: session.userId)
                if model.isDm, let people, let peer = model.peerUserId {
                    CvmStopOrderCard(seals: people.seals, userId: peer)
                }
                // The open bookings with the business, pinned above the timeline (ADR 0102).
                BookingCardStack(model: business)
                // Spec chat-timeline notes: one persistent notice per member whose assistant reads the chat.
                ForEach(model.assistantReaders) { reader in
                    AssistantReaderNotice(name: reader.name)
                }
            }
            rowList
                .environment(\.bipeCompanySeals, people?.seals)
                .environment(\.bipeHandles, people?.handles)
                // Spec timeline-media fromStranger: is the other person of the DM a contact?
                .task(id: model.peerUserId) {
                    let isContact = model.peerUserId.flatMap { people?.contactName(for: $0) } != nil
                    model.checkFromStranger(peerIsContact: isContact)
                }
            if let hint = model.inviterHint {
                InviterHintRow(hint: hint, inviterName: model.roomName) {
                    Task { await model.sendGreeting() }
                }
            }
            TypingLine(names: model.typingNames)
            // Spec chat-timeline profileNotice (ADR 0049): 1:1 chats only, amber, above the composer.
            if model.isDm, let people, let peer = model.peerUserId, peer != session.userId {
                ProfileNoticeBar(people: people, userId: peer, name: model.roomName)
            }
            if model.isDm, model.theftMark != nil {
                TheftMarkBanner(name: model.roomName)
            }
            if model.isMainTimeline {
                IdentityChangeBar(model: identity)
            }
            if model.isMainTimeline, model.selection == nil, model.successorRoomId == nil {
                AssistantDraftBanner(store: assistantDrafts, roomId: roomId, userId: session.userId) { draft in
                    if draft.threadRoot != nil {
                        // The thread view loads the thread's composer draft when it opens.
                        await assistantDrafts.keepAsThreadDraft(draft)
                        model.openThreadRoot = draft.threadRoot
                    } else {
                        await model.takeAssistantDraft(body: draft.body, replyTo: draft.replyTo)
                    }
                }
            }
        }
        // Spec composer: the composer floats at the bottom of the screen, over the scrolling messages.
        .safeAreaInset(edge: .bottom, spacing: 0) { VStack(spacing: 0) { bottomBar } }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .attachmentPicker(choice: $attaching,
                          onPicked: { urls in upload = PickedUpload(urls: urls, replyToEventId: model.takeReplyEventId()) },
                          onStickers: { showsStickers = true },
                          onLocation: { locationSend = LocationSendRequest(replyToEventId: model.takeReplyEventId()) },
                          onPoll: { creatingPoll = true })
        .fullScreenCover(item: $locationSend) { request in
            LocationSendView(timeline: model, session: session, replyToEventId: request.replyToEventId, close: { locationSend = nil })
        }
        .fullScreenCover(item: $upload) { upload in
            if let timeline = model.timeline {
                UploadPreviewView(model: UploadPreviewModel(urls: upload.urls, client: model.client, timeline: timeline,
                                                            replyToEventId: upload.replyToEventId),
                                  close: { self.upload = nil })
            }
        }
        .sheet(isPresented: $showsStickers) {
            StickerPickerView(model: StickerPickerModel(client: model.client, roomId: roomId), loader: media,
                              close: { showsStickers = false })
        }
        // message-actions viewStickerPack (spec sticker-pack).
        .sheet(item: $model.openingStickerPack) { pack in
            StickerPackView(model: StickerPackModel(ref: pack, client: model.client, handles: people?.handles), loader: media) {
                model.openingStickerPack = nil
            }
        }
        // Spec app-colors, ADR 0213: the chat background, still behind the scrolling messages.
        .background(ChatBackgroundView().ignoresSafeArea())
        // The composer sits at the bottom: the toast goes above it.
        .toast($toast, bottom: 96)
        .onChange(of: model.notice) { _, notice in
            if let notice { toast = notice.text }
        }
        .navigationTitle(titleText)
        .navigationBarTitleDisplayMode(.inline)
        // Spec app-tabs: the tab bar hides while a chat or thread is open.
        .toolbar(.hidden, for: .tabBar)
        // Spec threads openThread: the thread view as a sheet over the main timeline.
        .sheet(item: Binding(get: { model.openThreadRoot.map(ThreadSheetId.init) }, set: { model.openThreadRoot = $0?.id })) { thread in
            NavigationStack {
                ChatTimelineView(session: session, media: media, roomId: roomId, people: people, threadRoot: thread.id)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(BipeStrings.appClose) { model.openThreadRoot = nil }
                        }
                    }
            }
        }
        // Spec chat-timeline dayFocus: the day view over the main timeline; Fechar goes back to the newest messages.
        .sheet(item: $model.openDay) { day in
            NavigationStack {
                ChatTimelineView(session: session, media: media, roomId: roomId, people: people, day: day)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button(BipeStrings.appClose) { model.openDay = nil }
                        }
                    }
            }
        }
        // Spec chat-timeline jumpToDate.
        .sheet(isPresented: $pickingDate) {
            JumpToDateSheet(pick: { day in
                pickingDate = false
                Task { await model.jumpToDate(day) }
            }, cancel: { pickingDate = false })
        }
        .toolbar {
            if model.isMainTimeline {
                ToolbarItem(placement: .principal) {
                    if model.isDm, let openInfo { Button(action: openInfo) { chatTitle } }
                    else { chatTitle }
                }
            }
            if model.isGroup, model.isMainTimeline {
                ToolbarItem(placement: .primaryAction) {
                    // Spec chat-timeline notes: the group menu, with group-details and searchInChat.
                    Menu {
                        Button { showsGroupDetails = true } label: { Label(chatInfoTitle, systemImage: "info.circle") }
                        Button { searchingChat = true } label: { Label(BipeStrings.searchInChat, systemImage: "magnifyingglass") }
                    } label: {
                        Label {
                            Text(chatInfoTitle)
                        } icon: {
                            PhosphorIcon.info.toolbarImage(size: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                }
            }
            if model.isDm, model.isMainTimeline {
                ToolbarItem(placement: .primaryAction) {
                    // Spec chat-timeline notes: the DM chat menu is the one button on the right. Archive, never delete.
                    Menu {
                        Button { searchingChat = true } label: { Label(BipeStrings.searchInChat, systemImage: "magnifyingglass") }
                        if let openInfo {
                            Button(action: openInfo) { Label(BipeStrings.dmDetailsTitle, systemImage: "info.circle") }
                        }
                        Button { showsNotifications = true } label: { Label(BipeStrings.settingsNotifications, systemImage: "bell") }
                        Button { showsGallery = true } label: { Label(BipeStrings.galleryTitle, systemImage: "photo.on.rectangle") }
                        CallMenuItems(model: model, session: session, focus: callFocus)
                        BusinessMenuItems(model: business)
                        if let archive {
                            let archived = archive.isArchived()
                            Button {
                                archive.set(!archived)
                                if !archived { closeChat() }
                            } label: {
                                Label(archived ? BipeStrings.unarchiveAction : BipeStrings.archiveAction,
                                      systemImage: archived ? "tray.and.arrow.up" : "archivebox")
                            }
                        }
                    } label: {
                        Label {
                            Text(BipeStrings.chatActions)
                        } icon: {
                            PhosphorIcon.dotsThree.toolbarImage(size: 22)
                        }
                    }
                    .labelStyle(.iconOnly)
                    .task(id: session.userId) { callFocus = await CallCenter.shared.focusUrl(for: session) }
                }
            }
        }
        .sheet(isPresented: $showsNotifications) {
            NavigationStack { ChatNotificationsView(session: session, roomId: roomId) }
        }
        .sheet(isPresented: $showsGallery) {
            ChatMediaGalleryView(session: session, media: media, roomId: roomId)
        }
        .navigationDestination(isPresented: $showsGroupDetails) {
            GroupDetailsView(session: session, media: media, roomId: roomId)
        }
        // Spec chat-search with roomId: a message hit scrolls this chat to it.
        .sheet(isPresented: $searchingChat) {
            ChatMessageSearchView(session: session, roomId: roomId) { eventId in
                Task { await model.openOriginal(eventId: eventId) }
            }
        }
        .sheet(isPresented: $creatingPoll) {
            PollCreateView(timeline: model)
        }
        .sheet(item: Binding(get: { openPollId.map(PollSheetId.init) }, set: { openPollId = $0?.id })) { poll in
            PollDetailView(timeline: model, session: session, eventId: poll.id)
        }
        .accessibilityIdentifier(BipeScreen.chatTimeline.rawValue)
        .task {
            model.handles = people?.handles
            await model.start()
        }
        .task(id: model.isGroup) { if model.isGroup { await pinned.observe() } }
        .task(id: model.isGroup) { if model.isGroup { isNotes = await NotesRoom(session: session).isNotes(roomId: roomId) } }
        .task(id: startAttachment) { if let startAttachment { attaching = startAttachment } }
        // Spec chat-search openMessage (bipe#671): scroll to the message once the first rows are in.
        .task(id: focusEventId) {
            guard let focusEventId else { return }
            for _ in 0..<20 where model.rows.isEmpty {
                try? await Task.sleep(for: .milliseconds(250))
            }
            await model.openOriginal(eventId: focusEventId)
        }
        .task { await identity.observe() }
        .businessChatTools(business)
        .task(id: "\(model.isDm)|\(model.peerUserId ?? "")|\(model.roomName)") {
            await business.load(peerUserId: model.isDm ? model.peerUserId : nil, peerName: model.roomName)
        }
        .onDisappear {
            voicePlayer.stop()
            model.close()
        }
        .fullScreenCover(isPresented: Binding(get: { viewerRowId != nil }, set: { if !$0 { viewerRowId = nil } })) {
            if let viewerRowId {
                if let row = model.row(viewerRowId), let message = row.message, let location = message.location {
                    LocationFullView(location: location, senderName: message.senderName, sentAt: message.sentAt,
                                     close: { self.viewerRowId = nil })
                } else if let row = model.row(viewerRowId), let message = row.message, let live = message.live,
                          let location = live.location {
                    // Spec location-view: the pin follows the newest beacon while the full map is open.
                    LocationFullView(location: location, senderName: message.senderName, sentAt: message.sentAt, live: live,
                                     close: { self.viewerRowId = nil })
                } else {
                    MediaViewer(model: model, loader: media, close: { self.viewerRowId = nil }, rowId: viewerRowId)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await model.markAsRead() }
                model.refreshAssistantReaders()
            }
            if phase == .background { model.leave() }
        }
        .sheet(isPresented: presence(\.actionsRowId)) {
            if let row = model.row(model.actionsRowId) {
                MessageActionsSheet(model: model, row: row)
            }
        }
        .sheet(isPresented: presence(\.forwardingRowIds)) {
            if let rowIds = model.forwardingRowIds, case let contentJsons = model.forwardContents(rowIds: rowIds), !contentJsons.isEmpty {
                MessageForwardSheet(client: model.client, contentJsons: contentJsons, media: media) { model.forwardClosed(failed: $0) }
            }
        }
        .sheet(isPresented: presence(\.pickerOpenFor)) {
            EmojiPickerSheet { model.pickEmoji($0) }
        }
        .sheet(isPresented: presence(\.reactionSheet)) {
            if let reaction = model.reactionSheet {
                ReactionSendersSheet(reaction: reaction)
            }
        }
        .sheet(isPresented: presence(\.readersSheet)) {
            if let readers = model.readersSheet {
                ReadersSheet(readers: readers, media: media, handles: people?.handles)
            }
        }
        .sheet(isPresented: presence(\.report)) {
            MessageReportSheet(model: model)
        }
        // presenting: the alert clears confirmDeleteRowId through the binding before the Task runs, so the button keeps its own copy.
        .alert(BipeStrings.appMessageDeleteTitle, isPresented: presence(\.confirmDeleteRowId), presenting: model.confirmDeleteRowId) { rowId in
            Button(BipeStrings.appMessageActionDelete, role: .destructive) {
                Task { await model.confirmDelete(rowId: rowId) }
            }
            Button(BipeStrings.appCancel, role: .cancel) {
                model.confirmDeleteRowId = nil
            }
        } message: { _ in
            Text(BipeStrings.appMessageDeleteBody)
        }
        // Spec message-forward deleteSelection: one dialog for all own picked messages.
        .alert(BipeStrings.selectDeleteTitle, isPresented: $model.confirmsDeleteSelection) {
            Button(BipeStrings.appMessageActionDelete, role: .destructive) {
                Task { await model.confirmDeleteSelection() }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.selectDeleteBody)
        }
        // Spec notification-service: an open chat has no notifications.
        .onAppear { model.resumeMenuActions(); MissedCalls.clear(accountUserId: session.userId, roomId: model.roomId); NotificationActions.shared.chatOpened(BipeNotificationTarget(accountUserId: session.userId, roomId: model.roomId)) }
        .onDisappear {
            let target = BipeNotificationTarget(accountUserId: session.userId, roomId: model.roomId)
            if NotificationActions.shared.visibleRoom == target { NotificationActions.shared.chatOpened(nil) }
        }
        .notificationsPromptTrigger(userID: session.userId, messages: model.rows.compactMap(\.message))
    }

    private var chatInfoTitle: String {
        isNotes ? BipeStrings.dmDetailsTitle : BipeStrings.appGroupDetailsTitle
    }

    /// Spec dm-delete: events at or below the marker of a cleared DM are gone.
    private var shownRows: [TimelineRow] {
        model.rows.filter { row in row.message.map { cleared.keeps(roomId: roomId, sentAt: $0.sentAt) } ?? true }
    }

    /// A Bool binding for an optional piece of state: false sets it back to nil.
    private func presence<Value>(_ keyPath: ReferenceWritableKeyPath<ChatTimelineModel, Value?>) -> Binding<Bool> {
        Binding(get: { model[keyPath: keyPath] != nil },
                set: { if !$0 { model[keyPath: keyPath] = nil } })
    }

    nonisolated private static func isAtBottom(_ geometry: ScrollGeometry) -> Bool {
        geometry.contentOffset.y + geometry.containerSize.height - geometry.contentInsets.bottom
            >= geometry.contentSize.height - 40
    }

    private var rowList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    historyHeader
                    ForEach(shownRows) { row in
                        SelectableTimelineRow(model: model, row: row) {
                            TimelineRowView(row: row,
                                            isDm: model.isDm,
                                            isHighlighted: model.highlightedRowId == row.id,
                                            model: model,
                                            media: media,
                                            voicePlayer: voicePlayer,
                                            openPoll: { openPollId = $0 },
                                            openViewer: { viewerRowId = $0 })
                        }
                        .id(row.id)
                        .onAppear {
                            if row.id == model.rows.last?.id {
                                Task { await model.markAsRead() }
                            }
                        }
                    }
                    if model.dayFocus != nil {
                        newerFooter
                    }
                }
                .scrollTargetLayout()
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
            }
            .defaultScrollAnchor(model.dayFocus == nil ? .bottom : .top)
            .scrollDismissesKeyboard(.interactively)
            // Spec chat-timeline floatingDate: the day of the top row on screen, while the list scrolls.
            .onScrollTargetVisibilityChange(idType: String.self, threshold: 0.1) { ids in
                visibleRowIds = ids
            }
            .onScrollPhaseChange { _, phase, context in
                scrollPhase = phase
                if phase == .interacting { followsNewest = Self.isAtBottom(context.geometry) }
                floatingDateHide?.cancel()
                if phase.isScrolling {
                    if !showsFloatingDate { withBipeAnimation { showsFloatingDate = true } }
                } else {
                    floatingDateHide = Task {
                        try? await Task.sleep(for: .seconds(1))
                        guard !Task.isCancelled else { return }
                        withBipeAnimation { showsFloatingDate = false }
                    }
                }
            }
            .overlay(alignment: .top) {
                if showsFloatingDate, let day = floatingDay {
                    FloatingDatePill(day: day, pick: model.isMainTimeline ? { pickingDate = true } : nil)
                        .padding(.top, 4)
                        .transition(.opacity)
                }
            }
            // The chat opens at the newest message. The first rows and the history pages come in batches,
            // and the lazy stack guesses row heights, so the bottom anchor alone can land higher up.
            // Until the person scrolls up, each change of the content height scrolls to the newest message again.
            .onScrollGeometryChange(for: Bool.self, of: Self.isAtBottom) { _, atBottom in
                if atBottom {
                    followsNewest = true
                } else if scrollPhase == .interacting || scrollPhase == .decelerating {
                    followsNewest = false
                }
            }
            .onScrollGeometryChange(for: CGFloat.self, of: \.contentSize.height) { _, _ in
                if followsNewest, model.dayFocus == nil, let lastId = model.rows.last?.id {
                    proxy.scrollTo(lastId, anchor: .bottom)
                }
            }
            .onChange(of: model.rows.last?.id) { _, lastId in
                // The day view stays on the picked day when newer events load at the bottom.
                guard let lastId, model.dayFocus == nil else { return }
                // The person's own message always returns to the newest message.
                if model.rows.last?.message?.isOwn == true { followsNewest = true }
                if followsNewest {
                    withBipeAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                }
            }
            .onChange(of: model.scrollTarget) { _, target in
                guard let target else { return }
                followsNewest = false
                withBipeAnimation { proxy.scrollTo(target, anchor: .center) }
                model.scrolledToTarget()
            }
        }
    }

    /// The other person left the DM: the bar stays above the composer, which stays on (ADR 0141).
    @ViewBuilder
    private var leftBar: some View {
        if model.peerAway != .none {
            DmLeftBar(away: model.peerAway, name: model.roomName, inviting: model.invitingAgain) {
                Task {
                    let people = people
                    if let roomId = await model.inviteAgain(createDM: { peer in
                        guard let people else { throw CancellationError() }
                        return try await people.createDM(with: peer)
                    }) {
                        openOtherChat(roomId)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if model.selection != nil {
            // Spec message-forward selection: the bar takes the place of the composer.
            MessageSelectionBar(model: model)
        } else if model.dayFocus != nil {
            // Spec chat-timeline dayFocus: the day view is read-only, with no composer.
            EmptyView()
        } else if model.successorRoomId != nil {
            RoomUpgradeBar(model: model) { openOtherChat($0) }
        } else if model.isDm, let people, let peer = model.peerUserId {
            DmComposerSlot(people: people, peerUserId: peer, name: model.roomName) {
                leftBar
                composerBar
            }
        } else {
            leftBar
            composerBar
        }
    }

    private var composerBar: some View {
        ComposerBar(model: model, media: media, isNotes: isNotes, openAttachments: { attaching = $0 }, openStickers: { showsStickers = true })
            // Spec identity-change blocksSending: until the person taps Entendi.
            .disabled(identity.blocksSending)
            // Spec composer nameAmbiguous: the @ in the suggestion rows.
            .environment(\.bipeHandles, people?.handles)
    }

    private var chatTitle: some View {
        HStack(spacing: 8) {
            AvatarView(name: model.roomName, mxcUrl: model.roomAvatarUrl, loader: media, size: 32, userId: model.peerUserId, official: model.isNewsRoom,
                       members: model.avatarMembers)
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Text(model.roomName).font(.headline).foregroundStyle(BipeColor.inkPrimary).lineLimit(1)
                    if model.isNewsRoom || (model.isDm && BipeOfficialAccounts.isOfficial(model.peerUserId)) {
                        OfficialBadge()
                    }
                    if model.isDm {
                        PersonSealButton(userId: model.peerUserId)
                            .environment(\.bipeCompanySeals, people?.seals)
                    }
                }
                // Spec brand-mark: a reseller's mark under the title, never instead of the name.
                if model.isDm {
                    BrandMarkHeaderLine(userId: model.peerUserId, marks: people?.brandMarks)
                }
            }
        }
    }

    @ViewBuilder
    /// Spec chat-timeline floatingDate: the day of the top row among the rows on screen.
    private var floatingDay: Date? {
        let rows = shownRows
        let visible = Set(visibleRowIds)
        guard let top = rows.firstIndex(where: { visible.contains($0.id) }) else { return nil }
        return TimelineDays.day(at: top, in: rows)
    }

    /// The title: the chat name, the thread title, or the picked day in the day view.
    private var titleText: String {
        if let day = model.dayFocus?.day { return daySeparatorText(day) }
        return model.threadRoot == nil ? model.roomName : BipeStrings.threadTitle
    }

    /// Day view only: loads newer events while the bottom of the list is on screen (spec chat-timeline dayFocus).
    private var newerFooter: some View {
        Color.clear
            .frame(height: 1)
            .task(id: "\(model.rows.count)-\(model.newerLoads)") {
                await model.loadNewer()
            }
    }

    private var historyHeader: some View {
        Group {
            if model.isLoadingHistory && !model.reachedStart {
                Text(BipeStrings.appTimelineLoadingHistory)
            } else {
                Color.clear.frame(height: 1)
            }
        }
        .font(.footnote)
        .foregroundStyle(BipeColor.inkSecondary)
        .padding(.vertical, 8)
        // Runs while the top of the list is on screen, again after each load and each change of the rows
        // (action loadHistory). After a failed load, the model changes historyLoads after a pause.
        .task(id: "\(model.rows.count)-\(model.historyLoads)") {
            await model.loadHistory()
        }
    }

}

/// Spec typing-indicator: who is typing, above the composer.
private struct TypingLine: View {
    let names: [String]

    var body: some View {
        if let text {
            HStack(spacing: 6) {
                TypingDots()
                Text(text)
                    .font(.caption)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)
            .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
        }
    }

    private var text: String? {
        switch names.count {
        case 0: nil
        case 1: BipeStrings.appTypingOne(names[0])
        case 2: BipeStrings.appTypingTwo(names[0], names[1])
        default: BipeStrings.appTypingMany(names[0])
        }
    }
}

/// The calm three dots of the typing line.
private struct TypingDots: View {
    @State private var phase = 0.0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<3) { index in
                Circle()
                    .fill(BipeColor.glyphSecondary)
                    .frame(width: 5, height: 5)
                    .opacity(0.35 + 0.65 * max(0, sin(phase - Double(index) * 0.8)))
            }
        }
        .accessibilityHidden(true)
        .onAppear {
            // Reduce Motion: the dots stay still (bipe#496).
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

private struct PollSheetId: Identifiable {
    let id: String
}

/// Picked files for upload-preview, with the reply target taken when they were picked.
private struct PickedUpload: Identifiable {
    let id = UUID()
    let urls: [URL]
    let replyToEventId: String?
}

/// An open location-send screen and its reply target.
private struct LocationSendRequest: Identifiable {
    let id = UUID()
    let replyToEventId: String?
}

/// Spec composer: Markdown text, @ mentions in groups, the draft, and the entries to attachments,
/// stickers and voice. The reply bar (message-reply) and the edit bar (message-edit) sit above it.
private struct ComposerBar: View {
    @ObservedObject var model: ChatTimelineModel
    let media: MediaLoader
    /// Spec composer notes, Notes: no sticker button, and the short + menu.
    let isNotes: Bool
    let openAttachments: (AttachmentChoice) -> Void
    let openStickers: () -> Void
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.bipeHandles) private var handles
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 0) {
            if !model.canPost {
                Text(model.publicationClosed ? BipeStrings.groupPublicationOnlyBar : BipeStrings.dmNoPermissionToPost)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(16)
                    .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
            } else if let recording = model.recording {
                VoiceRecordingView(model: recording) { model.recording = nil }
                    .background(BipeColor.surfaceRaised.ignoresSafeArea(edges: .bottom))
            } else {
                // Spec composer: Liquid Glass controls over the scrolling messages; the bars that replace them stay solid.
                composer
            }
        }
        .accessibilityIdentifier(BipeScreen.composer.rawValue)
        .onChange(of: model.replyingToRowId) { _, rowId in
            if rowId != nil { focused = true }
        }
        .onChange(of: model.editing) { _, editing in
            if editing != nil { focused = true }
        }
    }

    @ViewBuilder
    private var composer: some View {
        if !model.suggestions.isEmpty || model.replyingTo != nil || model.editing != nil {
            VStack(spacing: 0) {
                if !model.suggestions.isEmpty {
                    suggestionList
                }
                if let reply = model.replyingTo {
                    bar(icon: .arrowBendUpLeft,
                        title: BipeStrings.appMessageReplyingTo(reply.senderName ?? ""),
                        preview: reply.preview,
                        onClose: model.cancelReply)
                } else if let editing = model.editing {
                    bar(icon: .pencilSimple,
                        title: BipeStrings.appMessageEditing,
                        preview: editing.originalText,
                        onClose: model.cancelEdit)
                }
            }
            .padding(.bottom, 8)
            .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 12)
            .padding(.top, 8)
        }
        HStack(alignment: .bottom, spacing: 8) {
            if model.editing == nil {
                AttachmentMenu(canPoll: model.canPoll, isNotes: isNotes, choose: openAttachments) {
                    iconImage(.plus)
                }
                .accessibilityLabel(BipeStrings.appComposerAttach)
            }
            HStack(alignment: .bottom, spacing: 4) {
                TextField(BipeStrings.appTimelineComposerPlaceholder,
                          text: Binding(get: { model.draft }, set: { model.editDraft($0) }),
                          axis: .vertical)
                    .focused($focused)
                    // A hardware keyboard: Return sends, Shift+Return makes a new line (bipe-chat/bipe#832). The
                    // on-screen keyboard does not send key presses, so its return key keeps making a new line.
                    .onKeyPress(.return, phases: .down) { press in
                        guard !press.modifiers.contains(.shift) else { return .ignored }
                        if model.canSend { Task { await model.send() } }
                        return .handled
                    }
                    .lineLimit(1...(verticalSizeClass == .compact ? 3 : 5))
                    // 22 pt line + 2 x 11 = 44 pt, the sticker button's height, so one line sits in the middle.
                    .padding(.vertical, 11)
                    .foregroundStyle(BipeColor.inkPrimary)
                if model.editing == nil, !isNotes {
                    Button(action: openStickers) {
                        PhosphorIcon.sticker.image
                            .resizable()
                            .frame(width: 20, height: 20)
                            .foregroundStyle(BipeColor.glyphSecondary)
                            .padding(.vertical, 8)
                            .bipeTapTarget()
                    }
                    .accessibilityLabel(BipeStrings.actionStickers)
                }
            }
            .padding(.leading, 16)
            .padding(.trailing, 4)
            .bipeGlass(in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            // The send button and the record button share one place (spec composer notes).
            if model.canSend || model.editing != nil {
                Button {
                    Task { await model.send() }
                } label: {
                    (model.editing == nil ? PhosphorIcon.paperPlaneRight : PhosphorIcon.check).image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .padding(11)
                        .foregroundStyle(model.canSend ? BipeColor.inkOnAccent : BipeColor.inkDisabled)
                        .bipeTapTarget()
                        .bipeGlass(in: Circle(), tint: model.canSend ? BipeColor.accentFill : nil)
                }
                .disabled(!model.canSend)
                .accessibilityLabel(model.editing == nil ? BipeStrings.appTimelineSend : BipeStrings.appMessageSaveEdit)
            } else {
                Button {
                    focused = false
                    model.startRecording()
                } label: {
                    PhosphorIcon.microphone.image
                        .resizable()
                        .frame(width: 22, height: 22)
                        .padding(11)
                        .foregroundStyle(BipeColor.glyphPrimary)
                        .bipeTapTarget()
                        .bipeGlass(in: Circle())
                }
                .accessibilityLabel(BipeStrings.appComposerRecord)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    /// The @ suggestions of a group (spec composer, ADR 0141).
    private var suggestionList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(model.suggestions) { suggestion in
                    Button {
                        model.pickSuggestion(suggestion)
                    } label: {
                        HStack(spacing: 10) {
                            AvatarView(name: suggestion.name, mxcUrl: suggestion.avatarUrl, loader: media, size: 32, userId: suggestion.userId)
                            VStack(alignment: .leading, spacing: 0) {
                                // Spec composer nameAmbiguous: the @ follows a name another member also has.
                                HStack(spacing: 6) {
                                    Text(suggestion.nameAmbiguous && suggestion.userId != nil && suggestion.handle == nil
                                         ? MemberNames.label(name: suggestion.name, userId: suggestion.userId ?? "", handles: handles)
                                         : suggestion.name)
                                        .foregroundStyle(BipeColor.inkPrimary)
                                    if BipeOfficialAccounts.isOfficial(suggestion.userId) { OfficialBadge() }
                                }
                                if let handle = suggestion.handle {
                                    Text(handle)
                                        .font(.caption)
                                        .foregroundStyle(BipeColor.inkSecondary)
                                }
                            }
                            Spacer()
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .frame(maxHeight: 200)
        .fixedSize(horizontal: false, vertical: true)
    }

    private func iconImage(_ icon: PhosphorIcon) -> some View {
        icon.image
            .resizable()
            .frame(width: 22, height: 22)
            .padding(11)
            .foregroundStyle(BipeColor.glyphPrimary)
            .bipeTapTarget()
            .bipeGlass(in: Circle())
    }

    private func bar(icon: PhosphorIcon, title: String, preview: String?, onClose: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            icon.image
                .resizable()
                .frame(width: 18, height: 18)
                .foregroundStyle(BipeColor.accentInk)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.bold())
                    .foregroundStyle(BipeColor.accentInk)
                if let preview {
                    Text(preview)
                        .font(.caption)
                        .foregroundStyle(BipeColor.inkSecondary)
                        .lineLimit(1)
                }
            }
            Spacer()
            Button(action: onClose) {
                PhosphorIcon.x.image
                    .resizable()
                    .frame(width: 16, height: 16)
                    .foregroundStyle(BipeColor.glyphSecondary)
                    .padding(6)
                    .bipeTapTarget()
            }
            .accessibilityLabel(BipeStrings.appCancel)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

/// signup-done notes: until the inviter's note arrives, the chat says the inviter knows, with a one-tap greeting.
private struct InviterHintRow: View {
    let hint: BipeInviterGreeting
    let inviterName: String
    let greet: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeStrings.inviterKnowsYouJoined(inviterName))
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            HStack {
                if let handle = hint.handle {
                    Text(BipeStrings.inviterHint(handle))
                        .font(.subheadline)
                        .foregroundStyle(BipeColor.inkPrimary)
                }
                Spacer()
                Button(action: greet) {
                    Text(BipeStrings.inviterHintAction)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
            }
        }
        .padding(12)
        .background(BipeColor.accentWash)
    }
}

/// Spec chat-timeline: the red caution card in a DM with a company that CVM ordered to stop its offers (ADR 0077).
/// From the signed badge only; it stays while the badge says so.
private struct CvmStopOrderCard: View {
    let seals: BipeCompanySeals
    let userId: String

    var body: some View {
        let cvm = seals.state(for: userId).cvm
        if cvm.danger, let date = cvm.lines.first?.arg {
            HStack(alignment: .top, spacing: 8) {
                PhosphorIcon.warningCircle.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .accessibilityHidden(true)
                Text(BipeStrings.cvmStopOrderCard(date))
                    .font(.subheadline.bold())
                    .foregroundStyle(BipeColor.trustDangerInk)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(BipeColor.trustDangerWash))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(BipeColor.trustDangerLine, lineWidth: 2))
            .padding(.horizontal, 12)
            .padding(.top, 8)
            .accessibilityElement(children: .combine)
        }
    }
}

/// The sheet item of spec threads openThread: the root event ID.
private struct ThreadSheetId: Identifiable {
    let id: String
}

/// Spec chat-archive from the DM chat menu: reads and sets the chat's archived state in the account's archive.
struct ChatArchiveToggle {
    let isArchived: @MainActor () -> Bool
    let set: @MainActor (Bool) -> Void
}
