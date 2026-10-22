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
    @State private var showsHandlePick = false
    @State private var path = NavigationPath()
    @State private var openingNotes = false
    @State private var notesFailed = false
    private enum Destination: Hashable { case notes(String) }

    init(model: AppTabsModel, chatList: ChatListModel, api: BipeAPIClient, onNewAccount: @escaping () -> Void) {
        self.model = model
        self.chatList = chatList
        self.onNewAccount = onNewAccount
        _settings = StateObject(wrappedValue: SettingsModel(session: chatList.session, api: api))
        _people = StateObject(wrappedValue: PeopleService(session: chatList.session, api: api, media: chatList.media))
        _handle = StateObject(wrappedValue: HandlePickModel(api: api, session: chatList.session))
    }

    var body: some View {
        NavigationStack(path: $path) {
            List {
                if let account = model.accounts.first(where: \.isCurrent) {
                    VoceHeader(account: account, media: chatList.media,
                               openProfile: { path.append(PeopleRoute.profileEdit) },
                               openQr: { path.append(PeopleRoute.profileLink) })
                        .listRowBackground(BipeColor.surfaceBase)
                }
                if model.accounts.count > 1 {
                    Section(BipeStrings.appAccountSwitcherTitle) {
                        AccountRows(model: model, fallbackMedia: chatList.media)
                    }
                }
                if model.canAddAccount {
                    Section {
                        Button(BipeStrings.appAccountNew, action: onNewAccount)
                            .listRowBackground(BipeColor.surfaceRaised)
                    }
                }
                Section {
                    Button(action: openNotes) {
                        HStack(spacing: 12) {
                            PhosphorIcon.notepad.image.resizable().frame(width: 24, height: 24)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(BipeStrings.selfChatTitle).foregroundStyle(BipeColor.inkPrimary)
                                Text(BipeStrings.selfChatSubtitle).font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                            }
                            if openingNotes { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(openingNotes)
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
                    ChatTimelineView(session: chatList.session, media: chatList.media, roomId: roomId)
                }
            }
            // Actions openProfile and openQr of spec voce: the people screens (bipe-chat/bipe#568).
            .navigationDestination(for: PeopleRoute.self) { route in
                switch route {
                case .profileLink:
                    ProfileLinkView(people: people, pickHandle: { showsHandlePick = true })
                default:
                    ProfileEditView(people: people)
                }
            }
        }
        .accessibilityIdentifier(BipeScreen.voce.rawValue)
        .task { await model.refreshProfiles() }
        .sheet(isPresented: $showsHandlePick) {
            HandlePickView(model: handle)
        }
        .alert(BipeStrings.selfChatError, isPresented: $notesFailed) {
            Button(BipeStrings.appRetry, action: openNotes)
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
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
        VStack(spacing: 8) {
            // Action openProfile: photo and name open profile-edit.
            Button(action: openProfile) {
                VStack(spacing: 8) {
                    AvatarView(name: account.displayName, mxcUrl: account.avatarUrl, loader: media, size: 88,
                               userId: account.userId, isCompany: account.isCompany)
                    Text(account.displayName)
                        .font(.title2.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                }
            }
            .buttonStyle(.plain)
            if let handle = account.handle {
                Text(verbatim: "@\(handle)")
                    .font(.subheadline)
                    .foregroundStyle(BipeColor.inkSecondary)
                if let url = URL(string: "https://bipe.chat/@\(handle)") {
                    ShareLink(item: url) { Text(BipeStrings.profileShare) }
                }
                // Action openQr: the profile link and its QR code (profile-link).
                Button(BipeStrings.profileQrTitle, action: openQr)
                    .buttonStyle(.borderless)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }
}
