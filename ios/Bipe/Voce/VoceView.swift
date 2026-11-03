// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec voce (ADR 0112): who you are first. The header shows photo and name, never the Matrix ID.
struct VoceView: View {
    @ObservedObject var model: AppTabsModel
    let chatList: ChatListModel
    let onNewAccount: () -> Void
    @StateObject private var settings: SettingsModel
    @StateObject private var people: PeopleService
    @StateObject private var handle: HandlePickModel
    @StateObject private var trust = TrustCardModel()
    private let api: BipeAPIClient
    @State private var showsHandlePick = false
    @State private var path = NavigationPath()
    @State private var openingNotes = false
    @State private var notesFailed = false
    @State private var govbrFlow: BipeGovbrFlow?
    private enum Destination: Hashable { case notes(String), invites, trust, myBookings, claim }

    init(model: AppTabsModel, chatList: ChatListModel, api: BipeAPIClient, onNewAccount: @escaping () -> Void) {
        self.model = model
        self.chatList = chatList
        self.onNewAccount = onNewAccount
        self.api = api
        _settings = StateObject(wrappedValue: SettingsModel(session: chatList.session, api: api))
        _people = StateObject(wrappedValue: PeopleService(session: chatList.session, api: api, media: chatList.media))
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: chatList.session))
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                // Spec voce (bipe-chat/bipe#988): the profile row, then the trust card. No prompt cards here
                // (bipe-chat/bipe#894): chat-list-prompt shows every ask.
                if let account = model.accounts.first(where: \.isCurrent) {
                    Section {
                        NavigationLink(value: SettingsRoute.group(.profile)) {
                            VoceProfileRow(account: account, media: chatList.media)
                        }
                        .accessibilityHint(BipeStrings.settingsProfile)
                    }
                    TrustCardSection(profile: trust.profile(for: account), actions: trustActions,
                                     openLadder: { path.append(Destination.trust) })
                }
                Section {
                    Button(action: openNotes) {
                        HStack(spacing: 12) {
                            SettingsIconTile(icon: .notepad)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(BipeStrings.selfChatTitle).foregroundStyle(BipeColor.inkPrimary)
                                Text(BipeStrings.selfChatSubtitle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                            }
                            Spacer()
                            if openingNotes { ProgressView() }
                        }
                    }
                    .disabled(openingNotes)
                    // Spec voce: a personal account has Meus agendamentos after Anotações (agenda-my-bookings).
                    if !model.currentIsCompany {
                        NavigationLink(value: Destination.myBookings) {
                            VoceTileLabel(icon: .clock, text: BipeStrings.agendaMyBookings)
                        }
                    }
                }
                Section {
                    NavigationLink(value: Destination.invites) {
                        VoceTileLabel(icon: .userPlus, text: BipeStrings.invitesTitle)
                    }
                }
                SettingsRootSection(model: settings)
                // Spec voce (MZ, 2 Nov 2026): no account rows; the Você tab switches (hold, double tap, VoiceOver
                // action). Adicionar conta is the last row, plain accent text (spec account-new).
                Section {
                    Button(action: onNewAccount) {
                        Text(BipeStrings.appAccountNew).foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.tabYou)
            .toolbar {
                // Spec voce openQr: the profile QR code; share and copy live on that screen (profile-link).
                ToolbarItem(placement: .topBarTrailing) {
                    Button { path.append(PeopleRoute.profileQr) } label: {
                        PhosphorIcon.qrCode.image.resizable().frame(width: 22, height: 22)
                    }
                    .accessibilityLabel(BipeStrings.profileQrAction)
                }
            }
            .settingsDestinations(model: settings, chatList: chatList) {
                Task { await model.start.signOut(userId: chatList.session.userId, alreadyLoggedOut: true) }
            }
            .navigationDestination(for: Destination.self) { destination in
                switch destination {
                case .notes(let roomId):
                    ChatTimelineView(session: chatList.session, media: chatList.media, roomId: roomId, people: people)
                case .invites:
                    InvitesView(model: model.invites(for: chatList.session), people: people,
                                openChat: { path.append(Destination.notes($0)) })
                case .trust:
                    if let account = model.accounts.first(where: \.isCurrent) {
                        TrustProfileView(profile: trust.profile(for: account), actions: trustActions)
                    }
                case .myBookings:
                    MyBookingsView(people: people, openChat: { path.append(Destination.notes($0)) })
                case .claim:
                    BusinessClaimView(session: chatList.session, api: api) {
                        let isCompany = model.accounts.first(where: \.isCurrent)?.isCompany ?? false
                        Task { await trust.load(session: chatList.session, api: api, isCompany: isCompany) }
                    }
                }
            }
            .navigationDestination(for: PeopleRoute.self) { route in
                peopleScreen(route)
            }
        }
        .accessibilityIdentifier(BipeScreen.voce.rawValue)
        .onAppear { people.start(); Task { await model.refreshProfiles() } }
        .task(id: model.accounts.first(where: \.isCurrent).map { "\($0.userId)|\($0.isCompany)" }) {
            let isCompany = model.accounts.first(where: \.isCurrent)?.isCompany ?? false
            await trust.load(session: chatList.session, api: api, isCompany: isCompany)
        }
        .sheet(isPresented: $showsHandlePick) { HandlePickView(model: handle) }
        // Spec govbr-verify: from the step 5 row only (bipe-chat/bipe#277).
        .sheet(item: $govbrFlow, onDismiss: { GovbrInbox.shared.end() }) { flow in
            GovbrVerifyView(flow: flow, onVerified: { level in
                trust.govbrVerified(level: level, userId: chatList.session.userId)
            }, close: { govbrFlow = nil })
        }
        .alert(BipeStrings.selfChatError, isPresented: $notesFailed) {
            Button(BipeStrings.appRetry, action: openNotes)
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
    }

    @ViewBuilder
    private func peopleScreen(_ route: PeopleRoute) -> some View {
        switch route {
        case .profileEdit:
            ProfileEditView(people: people)
        case .publicProfile:
            PublicProfileEditView(people: people, pickHandle: { showsHandlePick = true },
                                  openLink: { path.append(PeopleRoute.profileLink) })
        case .profileLink:
            ProfileLinkView(people: people, pickHandle: { showsHandlePick = true })
        case .profileQr:
            ProfileLinkView(people: people, pickHandle: { showsHandlePick = true }, initiallyShowsQr: true)
        case .contacts:
            ContactsView(people: people, openChat: { path.append(Destination.notes($0)) },
                         invite: { path.append(Destination.invites) })
        case .blockedList:
            BlockedListView(people: people) { path.append(PeopleRoute.person(userId: $0, fromDm: false)) }
        case .whoCanMessage:
            WhoCanMessageView(people: people)
        case .person(let userId, let fromDm):
            PersonProfileView(people: people, userId: userId, fromDm: fromDm,
                              routes: PersonProfileRoutes(openChat: { path.append(Destination.notes($0)) }))
        }
    }

    private var trustActions: TrustActions {
        TrustActions(addName: { path.append(PeopleRoute.profileEdit) },
                     addPhoto: { path.append(PeopleRoute.profileEdit) },
                     chooseHandle: { showsHandlePick = true },
                     openClaim: { path.append(Destination.claim) },
                     openGovbr: AppConfig.govbrEnabled ? {
                         govbrFlow = GovbrInbox.shared.begin(session: chatList.session, api: api)
                     } : nil)
    }

    private func openNotes() {
        guard !openingNotes else { return }
        openingNotes = true
        Task {
            defer { openingNotes = false }
            do { path.append(Destination.notes(try await chatList.openNotes())) }
            catch { if !Task.isCancelled { notesFailed = true } }
        }
    }
}

/// A Você row above Configurações: the settings icon tile and the title (spec voce).
private struct VoceTileLabel: View {
    let icon: PhosphorIcon
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            SettingsIconTile(icon: icon)
            Text(text).foregroundStyle(BipeColor.inkPrimary)
        }
    }
}

/// Spec voce profile row (bipe-chat/bipe#988): avatar, name and @ in one full-width row, as the iPhone's account row.
private struct VoceProfileRow: View {
    let account: AccountSummary
    let media: MediaLoader
    @ScaledMetric(relativeTo: .title3) private var side: CGFloat = 60

    var body: some View {
        HStack(spacing: 14) {
            AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: media, size: side,
                       userId: account.userId, isCompany: account.isCompany)
            VStack(alignment: .leading, spacing: 2) {
                Text(account.displayName).font(.title3.weight(.semibold)).foregroundStyle(BipeColor.inkPrimary)
                if let handle = account.handleLine {
                    Text(verbatim: handle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                }
            }
        }
        .padding(.vertical, 4)
    }
}
