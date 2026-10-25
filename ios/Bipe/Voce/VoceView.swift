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
    @State private var showsLinkPhone = false
    private enum Destination: Hashable { case notes(String), invites, trust }

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
                if let account = model.accounts.first(where: \.isCurrent) {
                    VoceHeader(account: account, media: chatList.media,
                               openProfile: { path.append(SettingsRoute.group(.profile)) },
                               openQr: { path.append(PeopleRoute.profileQr) })
                        .listRowBackground(Color.clear)
                        .listRowInsets(EdgeInsets())
                }
                // The accounts on this phone (ADR 0112, 0135); Nova conta closes the section (spec account-new).
                Section {
                    if model.accounts.count > 1 {
                        AccountRows(model: model, fallbackMedia: chatList.media)
                    }
                    Button(action: onNewAccount) {
                        IconLabel(icon: .plus, text: BipeStrings.appAccountNew, tint: BipeColor.accentInk)
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                } header: {
                    if model.accounts.count > 1 { Text(BipeStrings.appAccountSwitcherTitle) }
                }
                Section {
                    Button(action: openNotes) {
                        HStack(spacing: 12) {
                            PhosphorIcon.notepad.image.resizable().frame(width: 24, height: 24)
                                .foregroundStyle(BipeColor.accentInk)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(BipeStrings.selfChatTitle).foregroundStyle(BipeColor.inkPrimary)
                                Text(BipeStrings.selfChatSubtitle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                            }
                            Spacer()
                            if openingNotes { ProgressView() }
                        }
                    }
                    .disabled(openingNotes)
                    .listRowBackground(BipeColor.surfaceRaised)
                }
                // Perfil de confiança after Anotações (ADR 0112, spec trust-card).
                if let account = model.accounts.first(where: \.isCurrent) {
                    TrustCardSection(profile: trust.profile(for: account), actions: trustActions,
                                     openLadder: { path.append(Destination.trust) })
                }
                Section {
                    NavigationLink(value: Destination.invites) {
                        IconLabel(icon: .userPlus, text: BipeStrings.invitesTitle)
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                    // Spec voce openLinkPhone: the only way into link-phone-show (ADR 0059).
                    Button { showsLinkPhone = true } label: {
                        IconLabel(icon: .devices, text: BipeStrings.appLinkPhoneAction)
                    }
                    .listRowBackground(BipeColor.surfaceRaised)
                }
                SettingsRootSection(model: settings)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.tabYou)
            .settingsDestinations(model: settings) {
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
        .fullScreenCover(isPresented: $showsLinkPhone) {
            LinkComputerView(settings: settings, mode: .phone)
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
                     chooseHandle: { showsHandlePick = true })
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

private struct VoceHeader: View {
    let account: AccountSummary
    let media: MediaLoader
    let openProfile: () -> Void
    let openQr: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button(action: openProfile) {
                VStack(spacing: 6) {
                    AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: media, size: 96,
                               userId: account.userId, isCompany: account.isCompany)
                        .padding(.bottom, 6)
                    Text(account.displayName).font(.title2.bold()).foregroundStyle(BipeColor.inkPrimary)
                    if let handle = account.handle {
                        Text(verbatim: "@\(handle)").font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(BipeStrings.settingsProfile)
            // QR and Compartilhar perfil side by side, as the system's bordered buttons.
            HStack(spacing: 12) {
                Button(action: openQr) {
                    Label { Text(BipeStrings.profileQrAction) } icon: {
                        PhosphorIcon.qrCode.image.resizable().frame(width: 18, height: 18)
                    }
                    .frame(maxWidth: .infinity)
                }
                if let handle = account.handle, let url = URL(string: "https://bipe.chat/@\(handle)") {
                    ShareLink(item: url) {
                        Label { Text(BipeStrings.profileShare) } icon: {
                            PhosphorIcon.shareNetwork.image.resizable().frame(width: 18, height: 18)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .labelStyle(.titleAndIcon)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
