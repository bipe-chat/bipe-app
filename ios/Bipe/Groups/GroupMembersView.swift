// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec group-members. Groups only (RoomInfo.isDm false, ADR 0141).
struct GroupMembersView: View {
    @StateObject private var model: GroupMembersModel
    @State private var sheetMember: GroupMember?
    @State private var confirm: (action: GroupMembersModel.MemberAction, member: GroupMember)?
    @State private var toast: String?
    private let media: MediaLoader

    init(session: AccountSession, media: MediaLoader, roomId: String) {
        _model = StateObject(wrappedValue: GroupMembersModel(session: session, roomId: roomId))
        self.media = media
    }

    var body: some View {
        List {
            if model.canInvite {
                Section {
                    NavigationLink {
                        GroupInviteMembersView(session: model.session, media: media, mode: .invite(roomId: model.roomId)) { _ in
                            toast = BipeStrings.dmInviteToGroupSent
                            Task { await model.appear() }
                        }
                    } label: {
                        Label {
                            Text(BipeStrings.appGroupAddPeople)
                        } icon: {
                            PhosphorIcon.userPlus.image.resizable().frame(width: 20, height: 20)
                        }
                    }
                }
            }
            if model.loading {
                ProgressView().frame(maxWidth: .infinity)
            }
            if !model.joined.isEmpty {
                Section {
                    ForEach(model.joined) { member in
                        memberRow(member)
                    }
                } header: {
                    Text(BipeStrings.appGroupMembersJoined)
                }
            }
            if !model.invited.isEmpty {
                Section {
                    ForEach(model.invited) { member in
                        memberRow(member)
                    }
                } header: {
                    Text(BipeStrings.appGroupMembersInvited)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .searchable(text: Binding(get: { model.query }, set: { model.setQuery($0) }), prompt: BipeStrings.appPeopleSearch)
        .navigationTitle(BipeStrings.appGroupMembersTitle)
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(sheetMember?.person.displayName ?? "",
                            isPresented: Binding(get: { sheetMember != nil }, set: { if !$0 { sheetMember = nil } }),
                            titleVisibility: .visible,
                            presenting: sheetMember) { member in
            ForEach(model.actions(for: member), id: \.self) { action in
                Button(title(of: action), role: isDestructive(action) ? .destructive : nil) {
                    if action == .remove || action == .ban {
                        confirm = (action, member)
                    } else {
                        Task { await model.perform(action, on: member) }
                    }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
        .alert(confirm.map { title(of: $0.action) } ?? "",
               isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
               presenting: confirm) { pending in
            Button(title(of: pending.action), role: .destructive) {
                Task { await model.perform(pending.action, on: pending.member) }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: { pending in
            Text(pending.action == .ban
                 ? BipeStrings.appGroupMemberBanBody(pending.member.person.displayName)
                 : BipeStrings.appGroupMemberRemoveBody(pending.member.person.displayName))
        }
        .alert(BipeStrings.appGroupMemberError, isPresented: $model.showsError) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
        .toast($toast)
        .accessibilityIdentifier(BipeScreen.groupMembers.rawValue)
        .task { await model.appear() }
    }

    private func memberRow(_ member: GroupMember) -> some View {
        Button {
            // Action openMember. Nothing for the own user.
            if !model.actions(for: member).isEmpty {
                sheetMember = member
            }
        } label: {
            HStack(spacing: 12) {
                AvatarView(name: member.person.displayName, mxcUrl: member.person.avatarUrl, loader: media, size: 40)
                Text(member.person.displayName)
                    .foregroundStyle(BipeColor.inkPrimary)
                    .lineLimit(1)
                Spacer()
                if member.isYou {
                    Text(BipeStrings.appYou)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                } else if member.isAdmin {
                    Text(BipeStrings.appGroupAdmin)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.accentInk)
                }
            }
            .contentShape(Rectangle())
        }
    }

    private func title(of action: GroupMembersModel.MemberAction) -> String {
        switch action {
        case .viewProfile: BipeStrings.appGroupMemberProfile
        case .makeAdmin: BipeStrings.appGroupMemberMakeAdmin
        case .removeAdmin: BipeStrings.appGroupMemberRemoveAdmin
        case .remove: BipeStrings.appGroupMemberRemove
        case .ban: BipeStrings.appGroupMemberBan
        case .cancelInvite: BipeStrings.appGroupMemberCancelInvite
        }
    }

    private func isDestructive(_ action: GroupMembersModel.MemberAction) -> Bool {
        action == .remove || action == .ban || action == .cancelInvite
    }
}
