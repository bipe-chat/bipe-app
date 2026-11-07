// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI

/// Spec app-tabs: Conversas · Grupos · Você, then Buscar apart at the trailing end (ADR 0053, MZ decision 25 Oct 2026).
/// Keep it the last tab. iOS 27 draws a tab apart only with the `.prominent` role; there a `.search` tab only "may" get
/// that treatment, and on MZ's iPhone it did not. iOS 18 to 26 draw the `.search` role apart.
/// A company account has Trabalho in place of Grupos, and its groups in Conversas. An account with Mural on has its
/// groups in Conversas and no Grupos tab.
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
    /// Spec govbr-verify, action receiveDocument: a signed PDF from the share sheet or open with.
    @ObservedObject private var govbr = GovbrInbox.shared
    /// Spec chat-list muralEnabled: Conversas shows the groups too, so its badge counts them and no Grupos tab shows (spec app-tabs).
    @AppStorage private var muralEnabled: Bool
    private struct InviteCode: Identifiable {
        let code: String
        var id: String { code }
    }

    init(model: AppTabsModel, session: AccountSession, api: BipeAPIClient, initialRoomId: String?) {
        self.model = model
        self.session = session
        self.api = api
        self.initialRoomId = initialRoomId
        _muralEnabled = AppStorage(wrappedValue: false, MuralSetting.key(userId: session.userId))
        _encryption = StateObject(wrappedValue: EncryptionStatusModel(session: session))
    }

    private static var searchRole: TabRole {
        if #available(iOS 27.0, *) {
            return .prominent
        }
        return .search
    }

    @State private var shopClaimNotice: String?

    /// ADR 0195 point 7: the records of a shop token move to this account, then the chat with the shop opens.
    private func runShopClaim(chatList: ChatListModel) async {
        guard let claim = ShopClaim.pending() else { return }
        guard let records = try? await session.business(api).claimWebRecords(business: claim.handle, token: claim.token) else {
            ShopClaim.clear()
            return
        }
        ShopClaim.clear()
        shopClaimNotice = BipeStrings.lojaClaimed("@\(claim.handle)")
        guard let businessId = records.bookings.first?.businessId ?? records.orders.first?.businessId else { return }
        let people = PeopleService(session: session, api: api, media: chatList.media)
        if let roomId = try? await people.openOrCreateDM(with: businessId) {
            model.selectTab(.conversas)
            TabsLinks.shared.link = .chat(roomId)
        }
    }

    var body: some View {
        let chatList = model.chatList(for: session)
        TabView(selection: Binding(get: { model.selectedTab }, set: { model.selectTab($0) })) {
            Tab(value: AppTabsModel.Tab.conversas) {
                ChatListView(model: chatList, scope: model.currentIsCompany || muralEnabled ? .todas : .conversas, api: api,
                             encryption: encryption, initialRoomId: initialRoomId)
            } label: {
                Label(BipeStrings.tabChats, systemImage: "bubble.left.and.bubble.right")
            }
            .badge(model.currentIsCompany || muralEnabled ? model.conversasUnread + model.gruposAttention : model.conversasUnread)

            // A company account has Trabalho in the place of Grupos (spec app-tabs, business-tools).
            if model.currentIsCompany {
                Tab(value: AppTabsModel.Tab.trabalho) {
                    WorkView(chatList: chatList, api: api, openChat: { roomId in
                        model.selectTab(.conversas)
                        TabsLinks.shared.link = .chat(roomId)
                    }, onLeft: {
                        await model.start.signOut(userId: session.userId, alreadyLoggedOut: true)
                    })
                    .id(session.userId)
                } label: {
                    Label(BipeStrings.tabWork, systemImage: "briefcase")
                }
            } else if !muralEnabled {
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
                // The current account's photo or initial (MZ, 2 Nov 2026); the generic person until it loads.
                Label {
                    Text(BipeStrings.tabYou)
                } icon: {
                    if let icon = model.voceIcon {
                        Image(uiImage: icon).renderingMode(.original)
                    } else {
                        Image(systemName: "person.crop.circle")
                    }
                }
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
        .onChange(of: muralEnabled) { _, _ in model.muralChanged() }
        .environment(\.openURL, OpenURLAction { url in
            // Message links use the same signed-in destinations as links received from the system.
            if let code = BipeInviteLink.code(from: url) {
                invitedCode = InviteCode(code: code)
                return .handled
            }
            if let code = BipeInviteLink.groupCode(from: url) {
                model.selectTab(.conversas)
                TabsLinks.shared.link = .groupLink(GroupLinkCode(code: code))
                return .handled
            }
            let link: TabsLink
            switch PeopleLink(url: url) {
            case .handle(let handle): link = .profile(handle)
            case .userId(let userId): link = .person(userId)
            case .queue(let handle): link = .queue(handle)
            case nil:
                guard let room = RoomLink(url: url) else { return .systemAction }
                link = .room(room)
            }
            model.selectTab(.conversas)
            TabsLinks.shared.link = link
            return .handled
        })
        .environment(\.openHistoryHelp, encryption.openSheet)
        .environment(\.encryptionStatus, encryption)
        .environment(\.phoneUnverified, encryption.verificationState == .unverified)
        .sheet(item: $encryption.route, onDismiss: encryption.sheetClosed) { route in
            EncryptionSheet(route: route, model: encryption, session: session)
        }
        .task { await encryption.observe() }
        // Settings opens with its subtitles and switches filled (SettingsModel keeps the last values per account).
        .task(id: session.userId) { await SettingsModel.prefetch(session: session, api: api) }
        .modifier(CallScreenCover())
        .modifier(IncomingCallCover(session: session))
        .background(TabBarGestures(voceLabel: BipeStrings.tabYou,
                                   onDoubleTap: { model.doubleTapVoce() },
                                   switcher: { bottomInset, close in
                                       AnyView(AccountSwitcherPanel(model: model, fallbackMedia: chatList.media,
                                                                    bottomInset: bottomInset,
                                                                    onNewAccount: { showsNewAccount = true }, close: close))
                                   },
                                   showsSwitchAction: model.accounts.count > 1,
                                   switcherRequests: model.accountSwitcherRequests))
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
        .task(id: session.userId) { await runShopClaim(chatList: chatList) }
        .toast($shopClaimNotice, bottom: 72)
        .onOpenURL { url in
            if ShopClaim.keep(url) {
                Task { await runShopClaim(chatList: chatList) }
                return
            }
            if govbr.open(url) { return }
            if let code = BipeInviteLink.groupCode(from: url) {
                model.selectTab(.conversas)
                TabsLinks.shared.link = .groupLink(GroupLinkCode(code: code))
                return
            }
            guard let code = BipeInviteLink.code(from: url) else { return }
            invitedCode = InviteCode(code: code)
        }
        // A result with no choice to make: a toast, as on Android (bipe/docs/design/toasts.md). Above the tab bar.
        .toast(Binding(get: { govbr.showsNoOpenVerification ? BipeStrings.govbrNoOpenVerification : nil },
                       set: { if $0 == nil { govbr.showsNoOpenVerification = false } }),
               bottom: 72)
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
