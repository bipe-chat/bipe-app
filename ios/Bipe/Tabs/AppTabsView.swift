// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-tabs: Conversas · Grupos · Você, then Buscar apart at the trailing end (ADR 0053, MZ decision 25 Oct 2026).
/// Keep it the last tab. iOS 27 draws a tab apart only with the `.prominent` role; there a `.search` tab only "may" get
/// that treatment, and on MZ's iPhone it did not. iOS 18 to 26 draw the `.search` role apart.
/// A company account has Trabalho in place of Grupos, and its groups in Conversas.
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
    /// Spec encryption-status: one model for the account. The chat list banner, the undecryptable message help and
    /// the oldMessages settings row all open its one sheet, presented here (bipe-chat/bipe#793).
    @StateObject private var encryption: EncryptionStatusModel
    private struct InviteCode: Identifiable {
        let code: String
        var id: String { code }
    }

    init(model: AppTabsModel, session: AccountSession, api: BipeAPIClient, initialRoomId: String?) {
        self.model = model
        self.session = session
        self.api = api
        self.initialRoomId = initialRoomId
        _encryption = StateObject(wrappedValue: EncryptionStatusModel(session: session))
    }

    private static var searchRole: TabRole {
        if #available(iOS 27.0, *) {
            return .prominent
        }
        return .search
    }

    var body: some View {
        let chatList = model.chatList(for: session)
        TabView(selection: Binding(get: { model.selectedTab }, set: { model.selectTab($0) })) {
            Tab(value: AppTabsModel.Tab.conversas) {
                ChatListView(model: chatList, scope: model.currentIsCompany ? .todas : .conversas, api: api,
                             encryption: encryption, initialRoomId: initialRoomId)
            } label: {
                Label(BipeStrings.tabChats, systemImage: "bubble.left.and.bubble.right")
            }
            .badge(model.currentIsCompany ? model.conversasUnread + model.gruposAttention : model.conversasUnread)

            // A company account has Trabalho in the place of Grupos (spec app-tabs, business-tools).
            if model.currentIsCompany {
                Tab(value: AppTabsModel.Tab.trabalho) {
                    WorkView(chatList: chatList, api: api, openChat: { roomId in
                        model.selectTab(.conversas)
                        TabsLinks.shared.link = .chat(roomId)
                    })
                    .id(session.userId)
                } label: {
                    Label(BipeStrings.tabWork, systemImage: "briefcase")
                }
            } else {
                Tab(value: AppTabsModel.Tab.grupos) {
                    ChatListView(model: chatList, scope: .grupos, api: api, encryption: encryption)
                } label: {
                    Label(BipeStrings.tabGroups, systemImage: "person.3")
                }
                .badge(model.gruposAttention)
            }

            Tab(value: AppTabsModel.Tab.voce) {
                VoceView(model: model, chatList: chatList, api: api, onNewAccount: { showsNewAccount = true })
            } label: {
                Label(BipeStrings.tabYou, systemImage: "person.crop.circle")
            }
            // The other-account dot (ADR 0135), as the system tab badge.
            .badge(model.otherAccountUnread ? Text("•") : nil)

            Tab(value: AppTabsModel.Tab.search, role: Self.searchRole) {
                AccountSearchView(model: chatList, api: api)
            } label: {
                Label(BipeStrings.appTabSearch, systemImage: "magnifyingglass")
            }
        }
        .environmentObject(model)
        .environment(\.openURL, OpenURLAction { url in
            // Message links use the same signed-in destinations as links received from the system.
            if let code = BipeInviteLink.code(from: url) {
                invitedCode = InviteCode(code: code)
                return .handled
            }
            let link: TabsLink
            switch PeopleLink(url: url) {
            case .handle(let handle): link = .profile(handle)
            case .userId(let userId): link = .person(userId)
            case .queue(let handle): link = .queue(handle)
            case nil: return .systemAction
            }
            model.selectTab(.conversas)
            TabsLinks.shared.link = link
            return .handled
        })
        .environment(\.openHistoryHelp, encryption.openSheet)
        .environment(\.encryptionStatus, encryption)
        .sheet(item: $encryption.route, onDismiss: encryption.sheetClosed) { route in
            EncryptionSheet(route: route, model: encryption, session: session)
        }
        .task { await encryption.observe() }
        .modifier(CallScreenCover())
        .modifier(IncomingCallCover(session: session))
        .background(TabBarGestures(voceLabel: BipeStrings.tabYou,
                                   onDoubleTap: { model.doubleTapVoce() },
                                   menu: { AccountMenu.make(model: model) { showsNewAccount = true } },
                                   switcher: { AnyView(AccountSwitcherPopover(model: model, fallbackMedia: chatList.media) {
                                       showsNewAccount = true
                                   }) },
                                   showsSwitchAction: model.accounts.count > 1))
        .accessibilityIdentifier(BipeScreen.appTabs.rawValue)
        .pixHandoff(chatList: chatList)
        .shareToChat(chatList: chatList, openChat: { roomId in
            model.selectTab(.conversas)
            TabsLinks.shared.link = .chat(roomId)
        })
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
