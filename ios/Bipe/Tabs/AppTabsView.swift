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
    /// invite-signed-in: a bipe.chat/convite link opened on this signed-in phone.
    @State private var invitedCode: InviteCode?
    @State private var newAccountStart: SignupStart?
    private struct InviteCode: Identifiable {
        let code: String
        var id: String { code }
    }

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

            // A company account gets the Agenda tab after Grupos (spec agenda-owner, ADR 0103).
            if model.currentIsCompany {
                Tab(value: AppTabsModel.Tab.agenda) {
                    AgendaOwnerView(chatList: chatList, api: api, openChat: { roomId in
                        model.selectTab(.conversas)
                        TabsLinks.shared.link = .chat(roomId)
                    })
                    .id(session.userId)
                } label: {
                    Label(BipeStrings.tabAgenda, systemImage: "calendar")
                }
            }

            Tab(value: AppTabsModel.Tab.voce) {
                VoceView(model: model, chatList: chatList, api: api, onNewAccount: { showsNewAccount = true })
            } label: {
                Label(BipeStrings.tabYou, systemImage: "person.crop.circle")
            }
            // The other-account dot (ADR 0135), as the system tab badge.
            .badge(model.otherAccountUnread ? Text("•") : nil)

            Tab(value: AppTabsModel.Tab.search, role: .search) {
                AccountSearchView(model: chatList, api: api)
            } label: {
                Label(BipeStrings.appTabSearch, systemImage: "magnifyingglass")
            }
        }
        .environmentObject(model)
        .background(TabBarGestures(voceLabel: BipeStrings.tabYou,
                                   onDoubleTap: { model.doubleTapVoce() },
                                   menu: { AccountMenu.make(model: model) { showsNewAccount = true } }))
        .accessibilityIdentifier(BipeScreen.appTabs.rawValue)
        .pixHandoff(chatList: chatList)
        .modifier(InviteJoinNotice(model: model.invites(for: session)))
        .sheet(isPresented: $showsNewAccount, onDismiss: { newAccountStart = nil }) {
            AccountNewView(tabs: model, session: session, api: api, preset: newAccountStart) { showsNewAccount = false }
        }
        .onOpenURL { url in
            guard let code = BipeInviteLink.code(from: url) else { return }
            invitedCode = InviteCode(code: code)
        }
        .sheet(item: $invitedCode) { invite in
            InviteSignedInView(code: invite.code, session: session, api: api, accountsOnPhone: model.accounts.map(\.userId),
                               close: { invitedCode = nil },
                               openChat: { roomId in
                                   invitedCode = nil
                                   model.selectTab(.conversas)
                                   TabsLinks.shared.link = .chat(roomId)
                               },
                               openProfile: { handle in
                                   invitedCode = nil
                                   model.selectTab(.conversas)
                                   TabsLinks.shared.link = .profile(handle)
                               },
                               newAccount: { start in
                                   invitedCode = nil
                                   newAccountStart = start
                                   // One sheet at a time: open Nova conta after this one has gone.
                                   Task {
                                       try? await Task.sleep(for: .milliseconds(500))
                                       showsNewAccount = true
                                   }
                               })
        }
    }
}
