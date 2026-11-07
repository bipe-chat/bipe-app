// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

struct MacConversationsView: View {
    @ObservedObject var workspace: MacWorkspace
    @ObservedObject var account: MacAccount
    @ObservedObject var list: MacRoomList
    let scope: MacDestination
    @ObservedObject var requests: MacChatRequests
    private var search: String { account.roomSearches[scope] ?? "" }
    private var searchBinding: Binding<String> {
        Binding(get: { search }, set: { account.roomSearches[scope] = $0 })
    }
    @State private var showingArchived = false
    @State private var startingChat = false
    @State private var selectedRequest: String?
    @State private var reordering = false
    @State private var notificationsRoom: String?
    @State private var photoPickerRoom: String?

    /// The account keeps the open chat of each destination, so switching destinations comes back to it.
    private var selectedRoom: Binding<String?> {
        Binding(get: { account.selectedRooms[scope] }, set: { account.selectedRooms[scope] = $0 })
    }
    private var selected: String? { account.selectedRooms[scope] }
    private var showingRequests: Bool {
        get { scope == .chats && account.showingRequests }
        nonmutating set { account.showingRequests = newValue }
    }
    private var scoped: [MacRoomSummary] { list.scoped(scope, isCompany: account.isCompany, archived: showingArchived) }
    private var favourites: [MacRoomSummary] {
        showingArchived || !search.isEmpty ? [] : list.favourites(scope, isCompany: account.isCompany)
    }
    private var rows: [MacRoomSummary] {
        scoped.filter {
            (search.isEmpty || $0.name.localizedStandardContains(search)) &&
                (showingArchived || !search.isEmpty || !$0.isFavourite)
        }
    }

    var body: some View {
        NavigationSplitView {
            VStack(alignment: .leading, spacing: 0) {
                if showingRequests {
                    requestsBox
                } else {
                    chatList
                }
            }
            .frame(minWidth: MacSidebarLayout.minimum, idealWidth: MacSidebarLayout.ideal, maxWidth: MacSidebarLayout.maximum)
            .background(BipeColor.surfaceBase)
            .background(MacSidebarSizing())
            .toolbar {
                ToolbarItem(placement: .navigation) { MacAccountSwitcher(workspace: workspace, account: account) }
                if scope == .chats {
                    ToolbarItem(placement: .automatic) {
                        Button { startingChat = true } label: {
                            Image(decorative: "pencil-simple").resizable().scaledToFit().frame(width: 18, height: 18)
                        }.help(BipeStrings.actionStartChat).accessibilityLabel(BipeStrings.actionStartChat)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: MacSidebarLayout.minimum, ideal: MacSidebarLayout.ideal, max: MacSidebarLayout.maximum)
        } detail: {
            if showingRequests {
                if let request = requests.visible.first(where: { $0.id == selectedRequest }) {
                    MacChatRequestView(request: request, requests: requests, account: account, opened: openChat, closed: { selectedRequest = nil })
                        .id(request.id).environment(\.bipeCompanySeals, account.seals)
                } else {
                    placeholder(BipeStrings.requestsTitle)
                }
            } else if let row = list.rows.first(where: { $0.id == selected }) {
                MacChatView(room: row.room, name: row.name, drafts: account.drafts, media: account.media, face: row.avatar,
                            forwardTargets: list.rows, opensPhotoPicker: photoPickerRoom == row.id).id(row.id)
                    .environment(\.bipeHandles, account.handles)
                    .environment(\.bipeCompanySeals, account.seals)
            } else {
                placeholder(scope.title)
            }
        }
        .searchable(text: searchBinding, placement: .sidebar, prompt: BipeStrings.appSearchPlaceholder)
        .onChange(of: showingArchived) { _, archived in
            // A chat that start chat just opened has no row yet, and stays selected.
            if list.rows.first(where: { $0.id == selected })?.isArchived != archived, list.rows.contains(where: { $0.id == selected }) {
                selectedRoom.wrappedValue = nil
            }
            reordering = false
        }
        .onChange(of: favourites.count) { _, count in if count < 2 { reordering = false } }
        .onDisappear { reordering = false }
        .sheet(isPresented: $startingChat) {
            MacStartChatSheet(account: account, opened: { room in startingChat = false; openChat(room) }, close: { startingChat = false })
        }
        .sheet(isPresented: Binding(get: { notificationsRoom != nil }, set: { if !$0 { notificationsRoom = nil } })) {
            if let row = list.rows.first(where: { $0.id == notificationsRoom }) {
                MacChatNotificationsSheet(row: row, client: account.client) { notificationsRoom = nil }
            }
        }
    }

    private func placeholder(_ title: String) -> some View {
        Text(title).macFont(.largeTitle).foregroundStyle(BipeColor.inkSecondary)
            .frame(maxWidth: .infinity, maxHeight: .infinity).background(BipeColor.surfaceBase)
    }

    /// The room opens as soon as sync lists it. The selection waits for its row.
    private func openChat(_ room: String) {
        showingRequests = false
        selectedRequest = nil
        showingArchived = false
        selectedRoom.wrappedValue = room
    }

    /// Spec chat-requests: the box. Nothing in it joins the chat list until accepted.
    @ViewBuilder private var requestsBox: some View {
        Button { showingRequests = false; selectedRequest = nil } label: {
            HStack(spacing: 6) {
                Image(decorative: "caret-left").resizable().scaledToFit().frame(width: 16, height: 16)
                Text(BipeStrings.requestsTitle).macFont(.headline)
            }.contentShape(Rectangle())
        }.buttonStyle(.plain).accessibilityLabel(scope.title).padding(.horizontal, 16).padding(.vertical, 8)
        List(selection: $selectedRequest) {
            ForEach(requests.visible) { request in
                MacRequestRow(request: request, media: account.media, seals: account.seals).tag(request.id)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
        }
        .listStyle(.plain).scrollContentBackground(.hidden)
        .overlay {
            if requests.visible.isEmpty {
                Text(BipeStrings.appRequestsEmpty).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
                    .padding(24).allowsHitTesting(false)
            }
        }
    }

    @ViewBuilder private var chatList: some View {
        if showingArchived {
            Button { showingArchived = false } label: {
                HStack(spacing: 6) {
                    Image(decorative: "caret-left").resizable().scaledToFit().frame(width: 16, height: 16)
                    Text(BipeStrings.archivedTitle).macFont(.headline)
                }.contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel(scope.title).padding(.horizontal, 16).padding(.vertical, 8)
        }
        if list.actionFailed { MacNotice(text: BipeStrings.errorGeneric, danger: true).padding(.horizontal, 16) }
        List(selection: selectedRoom) {
            // Spec chat-requests rowCount: 0 hides the row. The row stays at the top of Conversas.
            if scope == .chats, !showingArchived, search.isEmpty, !requests.visible.isEmpty {
                Button { showingRequests = true } label: { MacRequestsEntry(count: requests.visible.count) }
                    .buttonStyle(.plain).listRowSeparator(.hidden)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
            if !favourites.isEmpty {
                MacFavouritesGrid(rows: favourites, media: account.media, reordering: $reordering, selected: selectedRoom,
                                  move: { id, index in Task { await list.moveFavourite(id, to: index, shown: favourites.map(\.id)) } },
                                  menu: { row in rowMenu(row) })
                    .listRowSeparator(.hidden).listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
            }
            ForEach(rows) { row in
                MacChatRow(row: row, media: account.media).tag(row.id)
                    .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                    .contextMenu { rowMenu(row) }
                    .accessibilityActions { rowMenu(row) }
            }
            if !showingArchived {
                Button(BipeStrings.archivedTitle) { showingArchived = true }
                    .listRowSeparator(.hidden)
            }
            if list.rows.count >= 100 {
                Button(BipeStrings.actionShowMore) { list.loadMore() }.listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain).scrollContentBackground(.hidden)
        .overlay {
            if rows.isEmpty, favourites.isEmpty {
                if !list.loaded { ProgressView() }
                else {
                    Text(!search.isEmpty ? BipeStrings.appSearchNoResults(search)
                         : showingArchived ? BipeStrings.archivedEmpty
                         : scope == .groups ? BipeStrings.appGroupsEmpty : BipeStrings.appChatListEmpty)
                        .macFont(.body).foregroundStyle(BipeColor.inkSecondary).padding(24).allowsHitTesting(false)
                }
            }
        }
    }

    /// Spec chat-row-actions, in the shared order. A command the Mac has no handler for stays hidden.
    private func menuItems(_ row: MacRoomSummary) -> [BipeChatMenuItem] {
        BipeChatMenuItem.allCases.filter { item in
            switch item {
            case .sendMedia: !row.isArchived
            case .favourite: !row.isArchived && !row.isFavourite
            case .unfavourite: !row.isArchived && row.isFavourite
            case .reorderFavourites: !row.isArchived && row.isFavourite && favourites.count > 1
            case .markRead: row.attention.isUnread
            case .markUnread: !row.attention.isUnread
            case .notifications: true
            case .archive: !row.isArchived
            case .unarchive: row.isArchived
            case .details, .deleteDm: false
            }
        }
    }

    private func choose(_ item: BipeChatMenuItem, _ row: MacRoomSummary) {
        switch item {
        case .sendMedia:
            photoPickerRoom = row.id
            selectedRoom.wrappedValue = row.id
        case .favourite: Task { await list.setFavourite(row, value: true) }
        case .unfavourite: Task { await list.setFavourite(row, value: false) }
        case .reorderFavourites: reordering = true
        case .markRead: Task { await list.setUnread(row, value: false) }
        case .markUnread: Task { await list.setUnread(row, value: true) }
        case .notifications: notificationsRoom = row.id
        case .archive: Task { await list.setArchived(row, value: true) }
        case .unarchive: Task { await list.setArchived(row, value: false) }
        case .details, .deleteDm: break
        }
    }

    @ViewBuilder private func rowMenu(_ row: MacRoomSummary) -> some View {
        ForEach(menuItems(row), id: \.self) { item in
            Button(role: item.isDestructive ? .destructive : nil) { choose(item, row) } label: {
                Label(item.title, systemImage: item.symbol)
            }.disabled(list.busy.contains(row.id) && item != .notifications && item != .sendMedia)
        }
    }
}

private struct MacChatRow: View {
    let row: MacRoomSummary
    @ObservedObject var media: MacMedia
    var body: some View {
        HStack(spacing: 12) {
            MacAvatar(name: row.name, media: media, url: row.avatar.url, userID: row.avatar.userID, members: row.avatar.members)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(row.name).macFont(.headline).lineLimit(1)
                    Spacer(minLength: 0)
                    if let date = row.date {
                        Text(time(date)).macFont(.caption).monospacedDigit().foregroundStyle(BipeColor.inkSecondary)
                            .lineLimit(1).fixedSize(horizontal: true, vertical: false)
                    }
                }
                HStack(spacing: 8) {
                    Text(row.activity.previewText ?? "").macFont(.subheadline).lineLimit(2)
                        .foregroundStyle(BipeColor.inkSecondary).frame(maxWidth: .infinity, alignment: .leading)
                    if row.isMuted {
                        Image(decorative: "bell-slash").resizable().scaledToFit().frame(width: 16, height: 16).accessibilityHidden(true)
                    }
                    if row.mentions > 0 { Text("@").macFont(.footnote).foregroundStyle(BipeColor.accentInk).accessibilityLabel(BipeStrings.a11yMention) }
                    if row.unread > 0 {
                        Text(row.unread.formatted()).macFont(.caption).fontWeight(.semibold).padding(.horizontal, 6).padding(.vertical, 3)
                            .background(BipeColor.accentWash, in: Capsule())
                            .accessibilityLabel(BipeStrings.appChatListUnread(row.unread.formatted()))
                    } else if row.markedUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 8, height: 8).accessibilityLabel(BipeStrings.chatUnreadState)
                    }
                }
            }.frame(minHeight: 56)
        }.contentShape(Rectangle())
    }

    private func time(_ date: Date) -> String {
        if Calendar.autoupdatingCurrent.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }
}

private struct MacFavouritesGrid<MenuContent: View>: View {
    let rows: [MacRoomSummary]
    @ObservedObject var media: MacMedia
    @Binding var reordering: Bool
    @Binding var selected: String?
    let move: (String, Int) -> Void
    @ViewBuilder let menu: (MacRoomSummary) -> MenuContent

    var body: some View {
        VStack(spacing: 8) {
            if reordering {
                Text(BipeStrings.chatFavouritesReorderHint).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                Button(BipeStrings.chatFavouritesReorderDone) { reordering = false }
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 16) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    if reordering {
                        cell(row).draggable(row.id)
                            .dropDestination(for: String.self) { ids, _ in
                                guard let id = ids.first, id != row.id, rows.contains(where: { $0.id == id }) else { return false }
                                move(id, index); return true
                            }
                            .accessibilityElement(children: .ignore).accessibilityLabel(label(row))
                            .accessibilityActions {
                                if index > 0 { Button(BipeStrings.chatFavouriteMoveEarlier) { move(row.id, index - 1) } }
                                if index < rows.count - 1 { Button(BipeStrings.chatFavouriteMoveLater) { move(row.id, index + 1) } }
                            }
                    } else {
                        Menu { menu(row) } label: { cell(row) } primaryAction: { selected = row.id }
                            .menuIndicator(.hidden).menuStyle(.button).buttonStyle(.plain)
                            .accessibilityActions { menu(row) }.accessibilityLabel(label(row))
                    }
                }
            }
        }.accessibilityElement(children: .contain).accessibilityLabel(BipeStrings.chatFavourites)
    }

    private func label(_ row: MacRoomSummary) -> String {
        row.attention.isUnread ? row.name + ", " + BipeStrings.chatUnreadState : row.name
    }

    private func cell(_ row: MacRoomSummary) -> some View {
        let isSelected = selected == row.id
        return VStack(spacing: 6) {
            MacAvatar(name: row.name, media: media, url: row.avatar.url, userID: row.avatar.userID, members: row.avatar.members, size: 72)
                .overlay(alignment: .topTrailing) {
                    if row.attention.isUnread {
                        Circle().fill(BipeColor.accentFill).frame(width: 12, height: 12)
                            .overlay(Circle().stroke(BipeColor.surfaceBase, lineWidth: 2))
                    }
                }
            Text(row.name).macFont(.subheadline).lineLimit(1).frame(maxWidth: .infinity)
        }.padding(.vertical, 6).frame(maxWidth: .infinity)
            .background(isSelected ? BipeColor.accentWash : .clear, in: RoundedRectangle(cornerRadius: MacDesign.corner))
            .contentShape(Rectangle())
            .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
