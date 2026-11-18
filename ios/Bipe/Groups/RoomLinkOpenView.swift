// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec room-link-open.
struct RoomLinkOpenView: View {
    @StateObject private var model: RoomLinkOpenModel
    let media: MediaLoader
    /// The message of an event link, for openChat.
    private let eventId: String?
    /// Opens chat-timeline for the room, at the event when there is one; the sheet has closed.
    let openChat: (_ roomId: String, _ eventId: String?) -> Void
    @Environment(\.dismiss) private var dismiss

    init(session: AccountSession, link: RoomLink, media: MediaLoader, openChat: @escaping (_ roomId: String, _ eventId: String?) -> Void) {
        _model = StateObject(wrappedValue: RoomLinkOpenModel(client: session.client, link: link))
        self.media = media
        self.eventId = link.eventId
        self.openChat = openChat
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                switch model.phase {
                case .loading:
                    ProgressView()
                    Text(BipeStrings.appRoomLinkOpening)
                        .foregroundStyle(BipeColor.inkSecondary)
                case .preview, .knockSent, .inviteOnly:
                    AvatarView(name: model.name, mxcUrl: model.avatarUrl, loader: media, size: 72)
                    Text(model.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Text(BipeStrings.appRoomLinkMembers(String(model.memberCount)))
                        .foregroundStyle(BipeColor.inkSecondary)
                    if let topic = model.topic {
                        Text(topic)
                            .font(.subheadline)
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.center)
                            .lineLimit(4)
                    }
                    status
                case .notFound:
                    Text(BipeStrings.appRoomLinkNotFound)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                case .failed:
                    Text(BipeStrings.errorNetwork)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Button(BipeStrings.appRetry) { Task { await open() } }
                        .buttonStyle(.bipeProminent)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(BipeStrings.appClose) { dismiss() }
                }
            }
        }
        .tint(BipeColor.accentFill)
        .accessibilityIdentifier(BipeScreen.roomLinkOpen.rawValue)
        .presentationDetents([.medium, .large])
        .task { await open() }
    }

    @ViewBuilder
    private var status: some View {
        switch model.phase {
        case .knockSent:
            Text(BipeStrings.appRoomLinkKnockSent)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
        case .inviteOnly:
            Text(BipeStrings.appRoomLinkInviteOnly)
                .foregroundStyle(BipeColor.inkPrimary)
                .multilineTextAlignment(.center)
        default:
            if model.canKnock {
                Button(BipeStrings.appRoomLinkKnock) { Task { await model.knock() } }
                    .buttonStyle(.bipeProminent)
                    .disabled(model.busy)
            } else {
                Button(BipeStrings.appRoomLinkJoin) {
                    Task {
                        if let roomId = await model.join() { finish(roomId) }
                    }
                }
                .buttonStyle(.bipeProminent)
                .disabled(model.busy)
            }
        }
    }

    private func open() async {
        if let roomId = await model.open() { finish(roomId) }
    }

    private func finish(_ roomId: String) {
        dismiss()
        openChat(roomId, eventId)
    }
}
