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
    private enum Destination: Hashable { case notes(String), invites, trust, myBookings, claim }
    @State private var photoPromptDismissed = false
    @State private var osTheftPromptDone = OsTheftPromptMemory.isDone

    init(model: AppTabsModel, chatList: ChatListModel, api: BipeAPIClient, onNewAccount: @escaping () -> Void) {
        self.model = model
        self.chatList = chatList
        self.onNewAccount = onNewAccount
        self.api = api
        _settings = StateObject(wrappedValue: SettingsModel(session: chatList.session, api: api))
        _people = StateObject(wrappedValue: PeopleService(session: chatList.session, api: api, media: chatList.media))
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: chatList.session))
        _photoPromptDismissed = State(initialValue: UserDefaults.standard.bool(forKey: "bipe.photoPrompt.dismissed.\(chatList.session.userId)"))
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
                    // Spec voce photoPrompt (bipe-chat/bipe#200): one card while the account has no photo.
                    let slot = BipePromptSlot(userID: account.userId)
                    let showsPhotoPrompt = account.isLoaded && account.avatarUrl == nil && !photoPromptDismissed
                        && slot.allows(.photo)
                    if showsPhotoPrompt {
                        VocePromptCard(prompt: .photo, open: { path.append(PeopleRoute.profileEdit) }, dismiss: dismissPhotoPrompt)
                            .onAppear { slot.didShow(.photo) }
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    } else if !osTheftPromptDone, OsTheftPromptMemory.isDue(userID: account.userId),
                              slot.allows(.osTheftProtection) {
                        // Spec voce osTheftPrompt (bipe-chat/bipe#125): one day after this phone first saw the account.
                        VocePromptCard(prompt: .osTheftProtection, detail: OsTheftProtection.pathText,
                                       open: { finishOsTheftPrompt(); OsTheftProtection.openSettings() },
                                       dismiss: finishOsTheftPrompt)
                            .onAppear { slot.didShow(.osTheftProtection) }
                            .listRowBackground(Color.clear)
                            .listRowInsets(EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0))
                    }
                }
                // Spec voce (MZ decision 25 Oct 2026): no account rows; the Você tab switches (hold, double tap,
                // VoiceOver action). Nova conta stays here (spec account-new).
                Section {
                    Button(action: onNewAccount) {
                        IconLabel(icon: .plus, text: BipeStrings.appAccountNew, tint: BipeColor.accentInk)
                    }
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
                    // Spec voce openLinkPhone: the only way into link-phone-show (ADR 0059).
                    Button { showsLinkPhone = true } label: {
                        IconLabel(icon: .devices, text: BipeStrings.appLinkPhoneAction)
                    }
                }
                SettingsRootSection(model: settings)
                // Spec settings-root: a personal account has Meus agendamentos after the groups (agenda-my-bookings).
                if !model.currentIsCompany {
                    Section {
                        NavigationLink(value: Destination.myBookings) {
                            IconLabel(icon: .clock, text: BipeStrings.agendaMyBookings)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(BipeStrings.tabYou)
            .settingsDestinations(model: settings, onNewAccount: onNewAccount) {
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
                     chooseHandle: { showsHandlePick = true },
                     openClaim: { path.append(Destination.claim) })
    }

    private var photoPromptKey: String { "bipe.photoPrompt.dismissed.\(chatList.session.userId)" }

    /// Agora não: the photo prompt never comes back on this account.
    private func dismissPhotoPrompt() {
        UserDefaults.standard.set(true, forKey: photoPromptKey)
        photoPromptDismissed = true
    }

    /// Conferir proteção or Agora não: the prompt never comes back on this phone.
    private func finishOsTheftPrompt() {
        OsTheftPromptMemory.markDone()
        osTheftPromptDone = true
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

/// A slot prompt of registries/onboarding.json on the Você tab: photo (trigger voceWithoutPhoto) and
/// osTheftProtection (trigger voceAfterDays). `detail` is an extra line under the body.
private struct VocePromptCard: View {
    let prompt: BipeOnboardingPrompt
    var detail: String?
    let open: () -> Void
    let dismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(BipeLocalized.string(prompt.titleKey))
                .font(.headline)
                .foregroundStyle(BipeColor.inkPrimary)
            if let bodyKey = prompt.bodyKey {
                Text(BipeLocalized.string(bodyKey))
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            if let detail {
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            HStack(spacing: 16) {
                Button(action: open) {
                    Text(prompt.primary.title)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .foregroundStyle(BipeColor.inkOnAccent)
                        .background(Capsule().fill(BipeColor.accentFill))
                }
                ForEach(prompt.text, id: \.titleKey) { button in
                    Button(action: dismiss) {
                        Text(button.title)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(BipeColor.accentInk)
                    }
                }
            }
            .buttonStyle(.plain)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(BipeColor.accentWash))
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
            .buttonStyle(.bipeBordered)
            .controlSize(.large)
            .labelStyle(.titleAndIcon)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}
