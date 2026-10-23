// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec group-invite-members. Pushed on a navigation stack. In pick mode onDone gets the picked people; in invite
/// mode it gets the people invited, after every invite went out.
struct GroupInviteMembersView: View {
    @StateObject private var model: GroupInviteMembersModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.bipeAPI) private var api
    @State private var showingInvites = false
    private let media: MediaLoader
    private let session: AccountSession
    private let onDone: ([Person]) -> Void

    init(session: AccountSession, media: MediaLoader, mode: GroupInviteMembersModel.Mode, onDone: @escaping ([Person]) -> Void) {
        _model = StateObject(wrappedValue: GroupInviteMembersModel(session: session, mode: mode))
        self.session = session
        self.media = media
        self.onDone = onDone
    }

    var body: some View {
        List {
            Section {
                if model.loading {
                    ProgressView().frame(maxWidth: .infinity)
                }
                ForEach(model.contacts) { pick in
                    Button {
                        model.toggle(pick.person.userId)
                    } label: {
                        PickRow(pick: pick, media: media)
                    }
                    .listRowBackground(BipeColor.surfaceBase)
                }
            } header: {
                Text(BipeStrings.appPeopleContacts)
            } footer: {
                Text(BipeStrings.startChatSearchByHandle)
            }
            // Action inviteSomeoneNew: a personal Bipe invite for a person who is not on Bipe yet.
            if api != nil {
                Section {
                    Button(BipeStrings.contactsInvite) { showingInvites = true }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .searchable(text: Binding(get: { model.query }, set: { model.setQuery($0) }), prompt: BipeStrings.appPeopleSearch)
        .navigationTitle(BipeStrings.appGroupAddPeople)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if model.phase == .inviting {
                    ProgressView()
                } else {
                    Button(BipeStrings.appGroupInviteAction) {
                        Task { await invite() }
                    }
                    .disabled(model.selectedCount == 0)
                }
            }
        }
        .sheet(isPresented: $showingInvites) {
            if let api {
                InvitesView(session: session, api: api, media: media) { _ in }
            }
        }
        .accessibilityIdentifier(BipeScreen.groupInviteMembers.rawValue)
        .task { await model.appear() }
    }

    /// Action invite.
    private func invite() async {
        switch model.mode {
        case .pick:
            onDone(model.selectedPeople)
            dismiss()
        case .invite:
            let people = model.selectedPeople
            if await model.invite() {
                onDone(people)
                dismiss()
            }
        }
    }
}

private struct PickRow: View {
    let pick: PersonPick
    let media: MediaLoader

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(name: pick.person.displayName, mxcUrl: pick.person.avatarUrl, loader: media, size: 40, userId: pick.person.userId)
            VStack(alignment: .leading, spacing: 2) {
                Text(pick.person.displayName)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                if let note {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(isError ? BipeColor.trustDangerInk : BipeColor.inkSecondary)
                } else if let handle = pick.person.handle {
                    Text("@" + handle)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
            if pick.status == .selected {
                PhosphorIcon.check.image
                    .resizable()
                    .frame(width: 20, height: 20)
                    .foregroundStyle(BipeColor.accentInk)
            }
        }
        .contentShape(Rectangle())
        .opacity(pick.status == .member || pick.status == .invited ? 0.6 : 1)
        .accessibilityAddTraits(pick.status == .selected ? .isSelected : [])
    }

    private var note: String? {
        switch pick.status {
        case .member: BipeStrings.dmInviteToGroupAlreadyMember
        case .invited: BipeStrings.dmInviteToGroupSent
        case .refused: BipeStrings.appGroupInviteRefused(pick.person.displayName)
        case .failed: BipeStrings.appGroupInviteError(pick.person.displayName)
        case .selectable, .selected: nil
        }
    }

    private var isError: Bool {
        pick.status == .refused || pick.status == .failed
    }
}
