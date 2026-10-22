// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec app-tabs: Conversas · Grupos · Você, and search in the search tab role (ADR 0053).
struct AppTabsView: View {
    @ObservedObject var model: AppTabsModel
    let session: AccountSession
    let api: BipeAPIClient
    let initialRoomId: String?
    /// The entry to settings-root until Você holds it (bipe-chat/bipe#577, second step).
    let onOpenSettings: () -> Void
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let chatList = model.chatList(for: session)
        TabView(selection: Binding(get: { model.selectedTab }, set: { model.selectTab($0) })) {
            Tab(value: AppTabsModel.Tab.conversas) {
                ChatListView(model: chatList, scope: .conversas, api: api, initialRoomId: initialRoomId, onOpenSettings: onOpenSettings)
            } label: {
                Label { Text(BipeStrings.tabChats) } icon: { PhosphorIcon.chatCircle.image }
            }
            .badge(model.conversasUnread)

            Tab(value: AppTabsModel.Tab.grupos) {
                ChatListView(model: chatList, scope: .grupos, api: api)
            } label: {
                Label { Text(BipeStrings.tabGroups) } icon: { PhosphorIcon.usersThree.image }
            }
            .badge(model.gruposAttention)

            Tab(value: AppTabsModel.Tab.voce) {
                VoceView(model: model, chatList: chatList)
            } label: {
                Label { Text(BipeStrings.tabYou) } icon: { voceIcon }
            }

            Tab(value: AppTabsModel.Tab.search, role: .search) {
                ChatSearchView(model: chatList)
            } label: {
                Label { Text(BipeStrings.appTabSearch) } icon: { PhosphorIcon.magnifyingGlass.image }
            }
        }
        .background(TabBarGestures(voceLabel: BipeStrings.tabYou,
                                   onDoubleTap: { model.doubleTapVoce() },
                                   onLongPress: { model.longPressVoce() }))
        .accessibilityIdentifier(BipeScreen.appTabs.rawValue)
    }

    /// The person icon, with a dot when another account on this phone has unread messages (ADR 0135).
    private var voceIcon: Image {
        guard model.otherAccountUnread else { return PhosphorIcon.userCircle.image }
        return DotIcon.image(PhosphorIcon.userCircle, selected: model.selectedTab == .voce, scale: displayScale)
    }
}

/// Renders an icon with the unread dot at its top trailing corner. A tab icon is one image: the dot has to be in it.
private enum DotIcon {
    @MainActor
    static func image(_ icon: PhosphorIcon, selected: Bool, scale: CGFloat) -> Image {
        let content = icon.image
            .resizable()
            .frame(width: 26, height: 26)
            .foregroundStyle(selected ? BipeColor.accentInk : BipeColor.inkSecondary)
            .overlay(alignment: .topTrailing) {
                Circle()
                    .fill(BipeColor.accentFill)
                    .frame(width: 9, height: 9)
            }
            .frame(width: 28, height: 28)
        let renderer = ImageRenderer(content: content)
        renderer.scale = scale
        guard let image = renderer.uiImage else { return icon.image }
        return Image(uiImage: image.withRenderingMode(.alwaysOriginal))
    }
}

/// Search, in the search tab role: the current account's chats whose name matches.
private struct ChatSearchView: View {
    @ObservedObject var model: ChatListModel
    @State private var query = ""
    @State private var path: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            List(matches) { chat in
                Button {
                    path.append(chat.roomId)
                } label: {
                    ChatRow(chat: chat, media: model.media)
                }
                .listRowBackground(BipeColor.surfaceBase)
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.appTabSearch)
            .navigationDestination(for: String.self) { roomId in
                ChatTimelineView(session: model.session, media: model.media, roomId: roomId)
            }
        }
        .searchable(text: $query)
    }

    private var matches: [ChatSummary] {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return [] }
        return model.chats.filter { $0.name.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
    }
}
