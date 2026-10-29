// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// A matrix.to or matrix: link to a room, an alias or an event (spec room-link-open). User links stay PeopleLink.
struct RoomLink: Equatable, Identifiable {
    /// The room ID or the alias.
    let idOrAlias: String
    let isAlias: Bool
    /// The servers the link names, for the preview and the join.
    let via: [String]

    var id: String { idOrAlias }

    /// `nil` when the URL is not a group link: a user link, another kind of link or nothing Matrix.
    init?(url: URL) {
        guard url.scheme == "matrix" || url.host() == "matrix.to",
              let entity = parseMatrixEntityFrom(uri: url.absoluteString) else { return nil }
        switch entity.id {
        case .room(let id), .eventOnRoomId(let id, _):
            idOrAlias = id
            isAlias = false
        case .roomAlias(let alias), .eventOnRoomAlias(let alias, _):
            idOrAlias = alias
            isAlias = true
        case .user:
            return nil
        }
        via = entity.via
    }
}

/// Spec room-link-open: the group of a link, with join or knock.
@MainActor
final class RoomLinkOpenModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case preview
        case knockSent
        case inviteOnly
        case notFound
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var name = ""
    @Published private(set) var avatarUrl: String?
    @Published private(set) var topic: String?
    @Published private(set) var memberCount: UInt64 = 0
    @Published private(set) var canKnock = false
    @Published private(set) var busy = false

    private let client: Client
    private let link: RoomLink

    init(client: Client, link: RoomLink) {
        self.client = client
        self.link = link
    }

    /// Action open. Returns the room ID when the person is already in the group: the caller opens the chat.
    func open() async -> String? {
        phase = .loading
        if !link.isAlias, let room = try? client.getRoom(roomId: link.idOrAlias),
           let info = try? await room.roomInfo(), info.membership == .joined {
            return room.id()
        }
        do {
            let preview = link.isAlias
                ? try await client.getRoomPreviewFromRoomAlias(roomAlias: link.idOrAlias)
                : try await client.getRoomPreviewFromRoomId(roomId: link.idOrAlias, viaServers: link.via)
            let info = preview.info()
            if info.membership == .joined { return info.roomId }
            if info.roomType == .space {
                phase = .notFound
                return nil
            }
            name = info.name?.nilIfBlank ?? BipeStrings.groupCommonNoRoomName
            avatarUrl = info.avatarUrl
            topic = info.topic?.nilIfBlank
            memberCount = info.numJoinedMembers
            switch info.joinRule {
            case .invite?, .private?:
                phase = .inviteOnly
            case .knock?, .knockRestricted?:
                canKnock = true
                phase = .preview
            default:
                canKnock = false
                phase = .preview
            }
        } catch {
            phase = Self.isNotFound(error) ? .notFound : .failed
        }
        return nil
    }

    /// Action join. Returns the room ID on success: the caller opens the chat.
    func join() async -> String? {
        busy = true
        defer { busy = false }
        do {
            return try await client.joinRoomByIdOrAlias(roomIdOrAlias: link.idOrAlias, serverNames: link.via).id()
        } catch {
            phase = Self.isForbidden(error) ? .inviteOnly : .failed
            return nil
        }
    }

    /// Action knock.
    func knock() async {
        busy = true
        defer { busy = false }
        do {
            _ = try await client.knock(roomIdOrAlias: link.idOrAlias, reason: nil, serverNames: link.via)
            phase = .knockSent
        } catch {
            phase = Self.isForbidden(error) ? .inviteOnly : .failed
        }
    }

    private static func isForbidden(_ error: Error) -> Bool {
        if let error = error as? ClientError, case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error { return true }
        return false
    }

    private static func isNotFound(_ error: Error) -> Bool {
        if let error = error as? ClientError, case .MatrixApi(kind: .notFound, code: _, msg: _, details: _) = error { return true }
        return false
    }
}

/// Spec room-link-open.
struct RoomLinkOpenView: View {
    @StateObject private var model: RoomLinkOpenModel
    let media: MediaLoader
    /// Opens chat-timeline for the room; the sheet has closed.
    let openChat: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    init(session: AccountSession, link: RoomLink, media: MediaLoader, openChat: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: RoomLinkOpenModel(client: session.client, link: link))
        self.media = media
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
        openChat(roomId)
    }
}
