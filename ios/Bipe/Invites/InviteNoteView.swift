// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import SwiftUI
import UIKit

/// Spec invite-note (bipe-shared product/screens/invite-note.json).
@MainActor
final class InviteNoteModel: ObservableObject {
    enum Phase: Equatable {
        case editing
        case creating
        case created
        case failed(String)
    }

    @Published var name = ""
    @Published var note = BipeStrings.inviteNoteDefault
    @Published private(set) var phase = Phase.editing
    @Published private(set) var link: URL?
    @Published private(set) var expiresAt: Date?

    private let session: AccountSession
    private let api: BipeAPIClient

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    /// Action create: POST /v1/me/invites, keeps the link and the note on this phone, then names the invite.
    func create() async {
        guard phase != .creating else { return }
        phase = .creating
        let creator = BipeInviteCreator(apiClient: api, userID: session.userId, accessToken: { [session] in session.accessToken() })
        let trimmedNote = note.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let created = try await creator.create(note: trimmedNote.isEmpty ? nil : trimmedNote)
            let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedName.isEmpty {
                // The invite exists even when the name fails; the user can name it again in invites.
                _ = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) in
                    try await self.api.nameInvite(id: created.id, name: trimmedName, accessToken: token)
                }
            }
            link = created.url
            expiresAt = created.expiresAt
            phase = .created
        } catch {
            phase = .failed((error as? BipeAPIError)?.localizedMessage ?? BipeStrings.errorNetwork)
        }
    }
}

struct InviteNoteView: View {
    @StateObject private var model: InviteNoteModel
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false
    private let onClose: () -> Void

    init(session: AccountSession, api: BipeAPIClient, onClose: @escaping () -> Void = {}) {
        _model = StateObject(wrappedValue: InviteNoteModel(session: session, api: api))
        self.onClose = onClose
    }

    var body: some View {
        NavigationStack {
            Form {
                if model.phase == .created, let link = model.link {
                    createdSections(link)
                } else {
                    editingSections
                }
            }
            .disabled(model.phase == .creating)
            .scrollContentBackground(.hidden)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .navigationTitle(BipeStrings.invitesCreate)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { close() }
                }
                if model.phase != .created {
                    ToolbarItem(placement: .confirmationAction) {
                        if model.phase == .creating {
                            ProgressView()
                        } else {
                            Button(BipeStrings.invitesCreate) {
                                Task { await model.create() }
                            }
                        }
                    }
                }
            }
            .toast(Binding(get: { copied ? BipeStrings.appCopied : nil }, set: { if $0 == nil { copied = false } }))
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.inviteNote.rawValue)
    }

    @ViewBuilder
    private var editingSections: some View {
        Section {
            TextField(BipeStrings.invitesNamePlaceholder, text: $model.name)
        } header: {
            Text(BipeStrings.invitesNameAction)
        } footer: {
            Text(BipeStrings.invitesNameFooter)
        }
        Section {
            TextField(BipeStrings.inviteNotePlaceholder, text: $model.note, axis: .vertical)
                .lineLimit(3...8)
        } header: {
            Text(BipeStrings.inviteNoteTitle)
        } footer: {
            Text(BipeStrings.inviteNoteFooter)
        }
        switch model.phase {
        case .creating:
            Section {
                HStack(spacing: 8) {
                    ProgressView()
                    Text(BipeStrings.appInviteCreating)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            }
        case .failed(let message):
            Section {
                Text(message)
                    .foregroundStyle(BipeColor.trustDangerInk)
            }
        case .editing, .created:
            EmptyView()
        }
    }

    @ViewBuilder
    private func createdSections(_ link: URL) -> some View {
        Section {
            Text(link.absoluteString)
                .font(.body.monospaced())
                .foregroundStyle(BipeColor.inkPrimary)
                .textSelection(.enabled)
        } header: {
            Text(BipeStrings.invitesLink)
        } footer: {
            if let expiresAt = model.expiresAt {
                Text(BipeStrings.invitesExpires(expiresAt.formatted(date: .abbreviated, time: .omitted)))
            }
        }
        Section {
            ShareLink(item: BipeStrings.invitesShareMessage(link.absoluteString)) {
                Text(BipeStrings.appShare)
            }
            Button(BipeStrings.appCopy) {
                UIPasteboard.general.string = link.absoluteString
                copied = true
            }
        }
    }

    /// Action close. A created invite stays in invites.
    private func close() {
        dismiss()
        onClose()
    }
}
