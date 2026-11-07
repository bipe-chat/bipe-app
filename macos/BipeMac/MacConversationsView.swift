// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

struct MacConversationsView: View {
    @ObservedObject var account: MacAccount
    @ObservedObject var list: MacRoomList
    let scope: MacDestination
    @State private var search = ""
    @State private var showingArchived = false
    @State private var reordering = false

    /// The account keeps the open chat of each destination, so switching destinations comes back to it.
    private var selectedRoom: Binding<String?> {
        Binding(get: { account.selectedRooms[scope] }, set: { account.selectedRooms[scope] = $0 })
    }
    private var selected: String? { account.selectedRooms[scope] }
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
            }.background(BipeColor.surfaceBase)
                .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 440)
                .searchable(text: $search, placement: .sidebar, prompt: BipeStrings.appSearchPlaceholder)
        } detail: {
            if let row = list.rows.first(where: { $0.id == selected }) {
                MacChatView(room: row.room, name: row.name, drafts: account.drafts, media: account.media, face: row.avatar).id(row.id)
            } else {
                Text(scope.title).macFont(.largeTitle).foregroundStyle(BipeColor.inkSecondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(BipeColor.surfaceBase)
            }
        }
        .onChange(of: showingArchived) { _, _ in selectedRoom.wrappedValue = nil; reordering = false }
        .onChange(of: favourites.count) { _, count in if count < 2 { reordering = false } }
        .onDisappear { reordering = false }
    }

    @ViewBuilder private func rowMenu(_ row: MacRoomSummary) -> some View {
        if !row.isArchived {
            Button(row.isFavourite ? BipeStrings.chatUnfavouriteAction : BipeStrings.chatFavouriteAction) {
                Task { await list.setFavourite(row, value: !row.isFavourite) }
            }.disabled(list.busy.contains(row.id))
            if row.isFavourite, favourites.count > 1 {
                Button(BipeStrings.chatFavouritesReorderAction) { reordering = true }
            }
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
