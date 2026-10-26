// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

/// One invite as the card shows it: a new one from create, or a pending one from the list.
struct InviteCard: Identifiable {
    let id: String
    let url: URL?
    let expiresAt: Date
}

/// Spec invites: one create button, then two lists. A pending row opens its card; a joined row opens the chat.
struct InvitesView: View {
    @ObservedObject var model: InvitesModel
    let people: PeopleService
    let openChat: (String) -> Void
    @State private var creating = false
    @State private var card: InviteCard?
    @State private var renaming: BipeInvite?
    @State private var name = ""
    @State private var revoking: BipeInvite?

    var body: some View {
        List {
            Section {
                Button { model.beginCreate(); creating = true } label: {
                    Label { Text(BipeStrings.invitesCreate) } icon: { PhosphorIcon.plus.image.resizable().frame(width: 20, height: 20) }
                }
                .disabled((model.quota?.available ?? 0) <= 0)
            } footer: {
                Text(BipeInviteQuota.footer(available: model.quota?.available, nextAvailableAt: model.quota?.nextAvailableAt))
            }
            if model.phase == .loading { ProgressView().frame(maxWidth: .infinity) }
            if model.phase == .failed {
                Section {
                    Text(BipeStrings.errorNetwork)
                    Button(BipeStrings.appRetry) { Task { await model.refresh() } }
                }
            }
            if let error = model.error { Text(error).foregroundStyle(BipeColor.trustDangerInk) }
            if model.phase == .loaded && model.pending.isEmpty && model.joined.isEmpty {
                Text(BipeStrings.invitesEmpty).foregroundStyle(BipeColor.inkSecondary)
            }
            if !model.pending.isEmpty {
                Section(BipeStrings.invitesPendingHeader) {
                    ForEach(model.pending) { invite in
                        Button { card = InviteCard(id: invite.id, url: model.links[invite.id], expiresAt: invite.expiresAt) } label: {
                            PendingInviteRow(invite: invite, onThisPhone: model.links[invite.id] != nil)
                        }
                        .contextMenu { pendingActions(invite) }
                    }
                }
            }
            if !model.joined.isEmpty {
                Section(BipeStrings.invitesJoinedHeader) {
                    ForEach(model.joined) { invite in
                        JoinedInviteRow(invite: invite, people: people, openChat: openChat)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .navigationTitle(BipeStrings.invitesTitle)
        .accessibilityIdentifier(BipeScreen.invites.rawValue)
        .task { await model.refresh() }
        .refreshable { await model.refresh() }
        .sheet(isPresented: $creating) { InviteCreateView(model: model) }
        .sheet(item: $card) { InviteCardView(model: model, card: $0) }
        .alert(BipeStrings.invitesNameAction, isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField(BipeStrings.invitesNamePlaceholder, text: $name)
            Button(BipeStrings.appSave) {
                guard let id = renaming?.id else { return }
                Task {
                    do { try await model.setName(id: id, name: name) }
                    catch { model.error = BipeStrings.errorNetwork }
                }
            }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: { Text(BipeStrings.invitesNameFooter) }
        .confirmationDialog(BipeStrings.invitesRevoke, isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } })) {
            Button(BipeStrings.invitesRevoke, role: .destructive) {
                if let invite = revoking { Task { await model.revoke(invite) } }
            }
        }
    }

    /// Long press on a pending invite: share and copy need the link on this phone; rename and revoke work everywhere.
    @ViewBuilder private func pendingActions(_ invite: BipeInvite) -> some View {
        if let url = model.links[invite.id] {
            ShareLink(item: BipeStrings.invitesShareMessage(url.absoluteString)) {
                Label(BipeStrings.appShare, systemImage: "square.and.arrow.up")
            }
            Button { UIPasteboard.general.url = url } label: {
                Label(BipeStrings.appCopy, systemImage: "doc.on.doc")
            }
        }
        Button { name = invite.name ?? ""; renaming = invite } label: {
            Label(BipeStrings.invitesNameAction, systemImage: "pencil")
        }
        Button(role: .destructive) { revoking = invite } label: {
            Label(BipeStrings.invitesRevoke, systemImage: "trash")
        }
    }
}

private struct PendingInviteRow: View {
    let invite: BipeInvite
    let onThisPhone: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(invite.name?.isEmpty == false ? invite.name! : BipeStrings.invitesLink)
                    .foregroundStyle(BipeColor.inkPrimary)
                Text(onThisPhone
                     ? BipeStrings.invitesExpires(invite.expiresAt.formatted(date: .abbreviated, time: .omitted))
                     : BipeStrings.invitesOtherDevice)
                    .font(.caption).foregroundStyle(BipeColor.inkSecondary)
            }
            Spacer()
            PhosphorIcon.caretRight.image.resizable().frame(width: 16, height: 16).foregroundStyle(BipeColor.inkSecondary)
        }
    }
}

private struct JoinedInviteRow: View {
    let invite: BipeInvite
    let people: PeopleService
    let openChat: (String) -> Void
    @State private var person: Person?
    @State private var opening = false
    @State private var failed = false

    var body: some View {
        Button {
            guard let userId = invite.joinedUserId, !opening else { return }
            opening = true
            Task {
                defer { opening = false }
                do { openChat(try await people.openOrCreateDM(with: userId)) }
                catch { failed = true }
            }
        } label: {
            HStack {
                AvatarView(name: person?.displayName ?? "", mxcUrl: person?.avatarUrl, loader: people.media,
                           size: 40, userId: invite.joinedUserId)
                VStack(alignment: .leading) {
                    Text(person?.displayName ?? BipeStrings.invitesStatusUsed).foregroundStyle(BipeColor.inkPrimary)
                    Text(failed ? BipeStrings.errorNetwork : BipeStrings.invitesChat)
                        .font(.caption).foregroundStyle(BipeColor.inkSecondary)
                }
                Spacer()
                if opening { ProgressView() }
            }
        }
        .task(id: invite.joinedUserId) {
            if let userId = invite.joinedUserId { person = try? await people.person(userId) }
        }
    }
}

/// Spec invite-note, without the note: ask who it is for, create, then show the card.
struct InviteCreateView: View {
    @ObservedObject var model: InvitesModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false
    @FocusState private var focused: Bool

    var body: some View {
        if let created = model.created, model.error == nil {
            InviteCardView(model: model, card: InviteCard(id: created.id, url: created.url, expiresAt: created.expiresAt))
        } else {
            NavigationStack {
                Form {
                    Section {
                        TextField(BipeStrings.invitesNamePlaceholder, text: $name)
                            .focused($focused)
                            .submitLabel(.done)
                            .onSubmit(create)
                    } footer: { Text(BipeStrings.invitesNameFooter) }
                    if let error = model.error { Text(error).foregroundStyle(BipeColor.trustDangerInk) }
                }
                .scrollContentBackground(.hidden)
                .background(BipeColor.surfaceBase.ignoresSafeArea())
                .safeAreaInset(edge: .bottom) {
                    Button(action: create) {
                        HStack {
                            if busy { ProgressView() }
                            Text(busy ? BipeStrings.appInviteCreating
                                 : model.created == nil ? BipeStrings.invitesCreate : BipeStrings.appRetry)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeProminent)
                    .controlSize(.large)
                    .disabled(busy)
                    .padding()
                }
                .navigationTitle(BipeStrings.invitesNameAction)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(BipeStrings.appCancel) { dismiss() }.disabled(busy)
                    }
                }
                .onAppear { focused = true }
                .accessibilityIdentifier(BipeScreen.inviteNote.rawValue)
            }
            .tint(BipeColor.accentFill)
            .interactiveDismissDisabled(busy)
        }
    }

    private func create() {
        guard !busy else { return }
        busy = true
        Task { await model.create(name: name); busy = false }
    }
}

/// One invite: the QR code and the link to share, then rename and revoke. A new invite and a pending one look the same.
struct InviteCardView: View {
    @ObservedObject var model: InvitesModel
    let card: InviteCard
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    @State private var editing = false
    @State private var name = ""
    @State private var revoking = false

    private var invite: BipeInvite? { model.pending.first { $0.id == card.id } }
    private var title: String { invite?.name?.isEmpty == false ? invite!.name! : BipeStrings.invitesLink }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let url = card.url {
                        QRCodeImage(text: url.absoluteString).frame(maxWidth: 240, maxHeight: 240)
                        // State code: the other person can read it and type it on signup-invite-code.
                        if let code = BipeInviteLink.normalizedCode(url.absoluteString) {
                            VStack(spacing: 4) {
                                Text(BipeStrings.signupInviteCode)
                                    .font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                                Text(verbatim: code)
                                    .font(.title2.weight(.semibold).monospaced())
                                    .foregroundStyle(BipeColor.inkPrimary)
                                    .textSelection(.enabled)
                            }
                        }
                        Text(BipeStrings.invitesExpires(card.expiresAt.formatted(date: .abbreviated, time: .omitted)))
                            .font(.subheadline).foregroundStyle(BipeColor.inkSecondary)
                        VStack(spacing: 12) {
                            ShareLink(item: BipeStrings.invitesShareMessage(url.absoluteString)) {
                                Label { Text(BipeStrings.appShare) } icon: { PhosphorIcon.shareNetwork.image.resizable().frame(width: 20, height: 20) }
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bipeProminent)
                            Button { UIPasteboard.general.url = url; copied = true } label: {
                                Label { Text(copied ? BipeStrings.appCopied : BipeStrings.appCopy) } icon: { PhosphorIcon.copy.image.resizable().frame(width: 20, height: 20) }
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bipeBordered)
                        }
                        .controlSize(.large)
                        Text(BipeStrings.invitesQrFooter)
                            .font(.footnote).multilineTextAlignment(.center).foregroundStyle(BipeColor.inkSecondary)
                    } else {
                        Text(BipeStrings.invitesOtherDevice).foregroundStyle(BipeColor.inkSecondary)
                    }
                    if invite != nil {
                        Divider()
                        VStack(spacing: 4) {
                            Button(BipeStrings.invitesNameAction) { name = invite?.name ?? ""; editing = true }
                                .frame(maxWidth: .infinity, minHeight: 44)
                            Button(BipeStrings.invitesRevoke, role: .destructive) { revoking = true }
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                    }
                }
                .padding(24)
            }
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(BipeStrings.appClose) { dismiss() } }
            }
            .alert(BipeStrings.invitesNameAction, isPresented: $editing) {
                TextField(BipeStrings.invitesNamePlaceholder, text: $name)
                Button(BipeStrings.appSave) { Task {
                    do { try await model.setName(id: card.id, name: name) }
                    catch { model.error = BipeStrings.errorNetwork }
                } }
                Button(BipeStrings.appCancel, role: .cancel) {}
            } message: { Text(BipeStrings.invitesNameFooter) }
            .confirmationDialog(BipeStrings.invitesRevoke, isPresented: $revoking) {
                Button(BipeStrings.invitesRevoke, role: .destructive) {
                    if let invite { Task { await model.revoke(invite); dismiss() } }
                }
            }
            .accessibilityIdentifier(BipeScreen.inviteQr.rawValue)
        }
        .tint(BipeColor.accentFill)
    }
}

struct InviteJoinNotice: ViewModifier {
    @ObservedObject var model: InvitesModel
    func body(content: Content) -> some View { content.toast($model.notice) }
}
