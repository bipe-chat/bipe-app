// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI
import UIKit

struct InviteQr: Identifiable {
    let id: String
    let url: URL
    var name: String?
}

/// Spec invites: quota, pending links from this phone, joined people, naming and revocation.
struct InvitesView: View {
    @ObservedObject var model: InvitesModel
    let people: PeopleService
    let openChat: (String) -> Void
    @State private var creating = false
    @State private var qr: InviteQr?
    @State private var editing: BipeInvite?
    @State private var name = ""
    @State private var revoking: BipeInvite?

    var body: some View {
        List {
            Section {
                Button(BipeStrings.invitesCreate) { model.beginCreate(); creating = true }
                    .disabled((model.quota?.available ?? 0) <= 0)
            } footer: {
                Text(BipeInviteQuota.footer(available: model.quota?.available, nextAvailableAt: model.quota?.nextAvailableAt))
            }
            if model.phase == .loading { ProgressView() }
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
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(invite.name?.isEmpty == false ? invite.name! : BipeStrings.invitesStatusPending)
                                Text(BipeStrings.invitesExpires(invite.expiresAt.formatted(date: .abbreviated, time: .shortened)))
                                    .font(.caption).foregroundStyle(BipeColor.inkSecondary)
                                if model.links[invite.id] == nil {
                                    Text(BipeStrings.invitesOtherDevice).font(.caption).foregroundStyle(BipeColor.inkSecondary)
                                }
                                if model.notes.contains(invite.id) { Text(BipeStrings.inviteNoteTitle).font(.caption) }
                            }
                            Spacer()
                            Menu {
                                if let link = model.links[invite.id] {
                                    ShareLink(item: BipeStrings.invitesShareMessage(link.absoluteString)) { Text(BipeStrings.appShare) }
                                    Button(BipeStrings.invitesQrAction) { qr = InviteQr(id: invite.id, url: link, name: invite.name) }
                                }
                                Button(BipeStrings.invitesNameAction) { name = invite.name ?? ""; editing = invite }
                                Button(BipeStrings.invitesRevoke, role: .destructive) { revoking = invite }
                            } label: { PhosphorIcon.caretRight.image.resizable().frame(width: 20, height: 20) }
                            .accessibilityLabel(BipeStrings.invitesTitle)
                        }
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
        .sheet(isPresented: $creating) { InviteNoteView(model: model) }
        .sheet(item: $qr) { InviteQrView(invite: $0) }
        .alert(BipeStrings.invitesNameAction, isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField(BipeStrings.invitesNamePlaceholder, text: $name)
            Button(BipeStrings.appSave) {
                if let invite = editing { Task {
                    do { try await model.setName(id: invite.id, name: name) }
                    catch { model.error = BipeStrings.errorNetwork }
                } }
                editing = nil
            }
            Button(BipeStrings.appCancel, role: .cancel) { editing = nil }
        } message: { Text(BipeStrings.invitesNameFooter) }
        .confirmationDialog(BipeStrings.invitesRevoke,
                            isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } })) {
            Button(BipeStrings.invitesRevoke, role: .destructive) {
                if let invite = revoking { Task { await model.revoke(invite) } }
                revoking = nil
            }
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
                    Text(person?.displayName ?? BipeStrings.invitesStatusUsed)
                    Text(failed ? BipeStrings.errorNetwork : BipeStrings.invitesChat).font(.caption)
                }
                if opening { Spacer(); ProgressView() }
            }
        }
        .task(id: invite.joinedUserId) {
            if let userId = invite.joinedUserId { person = try? await people.person(userId) }
        }
    }
}

/// Spec invite-note: a first message stays on this phone until the invitee's DM exists.
struct InviteNoteView: View {
    @ObservedObject var model: InvitesModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var note = BipeStrings.inviteNoteDefault
    @State private var busy = false
    @State private var qr: InviteQr?
    @State private var copied = false

    var body: some View {
        NavigationStack {
            Form {
                if let created = model.created {
                    Section(BipeStrings.invitesLink) {
                        Text(created.url.absoluteString).textSelection(.enabled)
                        Text(BipeStrings.invitesExpires(created.expiresAt.formatted(date: .abbreviated, time: .shortened)))
                        ShareLink(item: BipeStrings.invitesShareMessage(created.url.absoluteString)) { Text(BipeStrings.appShare) }
                        Button(BipeStrings.appCopy) { UIPasteboard.general.url = created.url; copied = true }
                        Button(BipeStrings.invitesQrAction) { qr = InviteQr(id: created.id, url: created.url, name: name) }
                    }
                } else {
                    Section {
                        TextField(BipeStrings.invitesNamePlaceholder, text: $name)
                    } footer: { Text(BipeStrings.invitesNameFooter) }
                    Section {
                        TextField(BipeStrings.inviteNotePlaceholder, text: $note, axis: .vertical).lineLimit(3...8)
                    } header: { Text(BipeStrings.inviteNoteTitle) } footer: { Text(BipeStrings.inviteNoteFooter) }
                }
                if let error = model.error { Text(error).foregroundStyle(BipeColor.trustDangerInk) }
                if model.created == nil || model.error != nil {
                    Button {
                        busy = true
                        Task { await model.create(name: name, note: note); busy = false }
                    } label: {
                        if busy { ProgressView(); Text(BipeStrings.appInviteCreating) }
                        else { Text(model.created == nil ? BipeStrings.invitesCreate : BipeStrings.appRetry) }
                    }
                    .disabled(busy)
                }
                if copied { Text(BipeStrings.appCopied) }
            }
            .disabled(busy)
            .navigationTitle(BipeStrings.invitesCreate)
            .toolbar { Button(BipeStrings.appClose) { dismiss() }.disabled(busy) }
            .accessibilityIdentifier(BipeScreen.inviteNote.rawValue)
        }
        .interactiveDismissDisabled(busy)
        .sheet(item: $qr) { InviteQrView(invite: $0) }
    }
}

struct InviteJoinNotice: ViewModifier {
    @ObservedObject var model: InvitesModel
    func body(content: Content) -> some View { content.toast($model.notice) }
}

struct InviteQrView: View {
    let invite: InviteQr
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let name = invite.name, !name.isEmpty { Text(name).font(.headline) }
                QRCodeImage(text: invite.url.absoluteString).frame(maxWidth: 280, maxHeight: 280)
                Text(BipeStrings.invitesQrFooter).multilineTextAlignment(.center).foregroundStyle(BipeColor.inkSecondary)
                ShareLink(item: BipeStrings.invitesShareMessage(invite.url.absoluteString)) { Text(BipeStrings.appShare) }
            }
            .padding(24)
            .navigationTitle(BipeStrings.invitesQrTitle)
            .toolbar { Button(BipeStrings.appClose) { dismiss() } }
            .accessibilityIdentifier(BipeScreen.inviteQr.rawValue)
        }
    }
}
