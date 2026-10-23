// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-tabs: Conversas · Grupos · Você, and search in the search tab role (ADR 0053).
/// The standard tab bar with SF Symbols: the system sets their size, weight and selected state (ADR 0149).
struct AppTabsView: View {
    @ObservedObject var model: AppTabsModel
    let session: AccountSession
    let api: BipeAPIClient
    let initialRoomId: String?
    @State private var showsNewAccount = false
    @State private var pendingNewAccount = false

    var body: some View {
        let chatList = model.chatList(for: session)
        let account = model.accounts.first(where: \.isCurrent)
        TabView(selection: Binding(get: { model.selectedTab }, set: { model.selectTab($0) })) {
            Tab(value: AppTabsModel.Tab.conversas) {
                ChatListView(model: chatList, scope: .conversas, api: api, initialRoomId: initialRoomId,
                             account: account, onOpenVoce: { model.selectTab(.voce) })
            } label: {
                Label(BipeStrings.tabChats, systemImage: "bubble.left.and.bubble.right")
            }
            .badge(model.conversasUnread)

            Tab(value: AppTabsModel.Tab.grupos) {
                ChatListView(model: chatList, scope: .grupos, api: api, account: account,
                             onOpenVoce: { model.selectTab(.voce) })
            } label: {
                Label(BipeStrings.tabGroups, systemImage: "person.3")
            }
            .badge(model.gruposAttention)

            Tab(value: AppTabsModel.Tab.voce) {
                VoceView(model: model, chatList: chatList, api: api, onNewAccount: { showsNewAccount = true })
            } label: {
                Label(BipeStrings.tabYou, systemImage: "person.crop.circle")
            }
            // The other-account dot (ADR 0135), as the system tab badge.
            .badge(model.otherAccountUnread ? Text("•") : nil)

            Tab(value: AppTabsModel.Tab.search, role: .search) {
                ChatSearchView(model: chatList)
            } label: {
                Label(BipeStrings.appTabSearch, systemImage: "magnifyingglass")
            }
        }
        .environmentObject(model)
        .background(TabBarGestures(voceLabel: BipeStrings.tabYou,
                                   onDoubleTap: { model.doubleTapVoce() },
                                   onLongPress: { model.longPressVoce() }))
        .accessibilityIdentifier(BipeScreen.appTabs.rawValue)
        .pixHandoff(chatList: chatList)
        .modifier(InviteJoinNotice(model: model.invites(for: session)))
        .sheet(isPresented: $model.showsAccountSwitcher, onDismiss: {
            if pendingNewAccount {
                pendingNewAccount = false
                showsNewAccount = model.canAddAccount
            }
        }) {
            AccountSwitcherView(model: model, fallbackMedia: chatList.media) {
                pendingNewAccount = true
                model.showsAccountSwitcher = false
            }
        }
        .sheet(isPresented: $showsNewAccount) {
            SignupFlowView(api: api, onCancel: { showsNewAccount = false }) { session, outcome in
                showsNewAccount = false
                model.start.didSignUp(session, outcome: outcome)
            }
        }
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
