// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Spec invites. Shown as a sheet. onOpenChat opens the DM with a person who joined.
struct InvitesView: View {
    @StateObject private var model: InvitesModel
    @Environment(\.dismiss) private var dismiss
    @State private var creating = false
    @State private var naming: PersonalInvite?
    @State private var nameText = ""
    @State private var revoking: PersonalInvite?
    private let media: MediaLoader
    private let onOpenChat: (String) -> Void

    init(session: AccountSession, api: BipeAPIClient, media: MediaLoader, onOpenChat: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: InvitesModel(session: session, api: api))
        self.media = media
        self.onOpenChat = onOpenChat
    }

    var body: some View {
        NavigationStack {
            content
                .scrollContentBackground(.hidden)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .navigationTitle(BipeStrings.invitesTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appClose) { dismiss() }
                    }
                }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.invites.rawValue)
        .task { await model.appear() }
        .sheet(isPresented: $creating, onDismiss: { Task { await model.appear() } }) {
            InviteNoteView(session: model.session, api: model.api)
        }
        .alert(BipeStrings.invitesNameAction, isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
            TextField(BipeStrings.invitesNamePlaceholder, text: $nameText)
            Button(BipeStrings.appSave) {
                if let invite = naming {
                    Task { await model.setName(nameText, for: invite.id) }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.invitesNameFooter)
        }
        .confirmationDialog(BipeStrings.invitesRevoke,
                            isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }),
                            presenting: revoking) { invite in
            Button(BipeStrings.invitesRevoke, role: .destructive) {
                Task { await model.revoke(invite.id) }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
        .alert(model.error ?? "", isPresented: Binding(get: { model.error != nil && model.phase == .loaded }, set: { if !$0 { model.error = nil } })) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed:
            VStack(spacing: 12) {
                Text(BipeStrings.errorNetwork)
                    .foregroundStyle(BipeColor.inkSecondary)
                    .multilineTextAlignment(.center)
                Button(BipeStrings.appRetry) {
                    Task { await model.appear() }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .loaded:
            list
        }
    }

    private var list: some View {
        List {
            Section {
                Button {
                    creating = true
                } label: {
                    Label {
                        Text(BipeStrings.invitesCreate)
                    } icon: {
                        PhosphorIcon.plus.image.resizable().frame(width: 20, height: 20)
                    }
                }
                .disabled((model.available ?? 0) == 0)
            } footer: {
                Text(model.footer)
            }

            if model.pending.isEmpty && model.joined.isEmpty {
                Section {
                    Text(BipeStrings.invitesEmpty)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }

            if !model.pending.isEmpty {
                Section {
                    ForEach(model.pending) { invite in
                        pendingRow(invite)
                    }
                } header: {
                    Text(BipeStrings.invitesPendingHeader)
                }
            }

            if !model.joined.isEmpty {
                Section {
                    ForEach(model.joined) { invite in
                        joinedRow(invite)
                    }
                } header: {
                    Text(BipeStrings.invitesJoinedHeader)
                }
            }
        }
    }

    private func pendingRow(_ invite: PersonalInvite) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(invite.name ?? BipeStrings.invitesStatusPending)
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.invitesExpires(invite.expiresAt.formatted(date: .abbreviated, time: .omitted)))
                .font(.footnote)
                .foregroundStyle(BipeColor.inkSecondary)
            if invite.link == nil {
                Text(BipeStrings.invitesOtherDevice)
                    .font(.footnote)
                    .foregroundStyle(BipeColor.inkSecondary)
            }
            HStack(spacing: 16) {
                if let link = invite.link {
                    ShareLink(item: BipeStrings.invitesShareMessage(link.absoluteString)) {
                        Text(BipeStrings.appShare)
                    }
                }
                Button(BipeStrings.invitesNameAction) {
                    nameText = invite.name ?? ""
                    naming = invite
                }
                Button(BipeStrings.invitesRevoke, role: .destructive) {
                    revoking = invite
                }
            }
            .font(.subheadline)
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }

    private func joinedRow(_ invite: PersonalInvite) -> some View {
        HStack(spacing: 12) {
            AvatarView(name: invite.joined?.displayName ?? "", mxcUrl: invite.joined?.avatarUrl, loader: media, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Text(invite.joined?.displayName ?? invite.name ?? BipeStrings.invitesStatusUsed)
                    .foregroundStyle(BipeColor.inkPrimary)
                if let name = invite.name {
                    Text(name)
                        .font(.footnote)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
            Spacer()
            if let userId = invite.joined?.userId, let roomId = model.dmRoomId(with: userId) {
                Button(BipeStrings.invitesChat) {
                    dismiss()
                    onOpenChat(roomId)
                }
                .buttonStyle(.borderless)
            }
        }
    }
}
