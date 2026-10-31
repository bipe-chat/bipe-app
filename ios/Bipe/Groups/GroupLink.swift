// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import MatrixRustSDK
import SwiftUI

/// Spec group-link (ADR 0184, bipe-chat/bipe#744): one link that brings people into the group.
@MainActor
final class GroupLinkModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case none
        case active
        case working
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var link: BipeGroupLink?
    /// The link URL, when this phone made the link.
    @Published private(set) var url: URL?
    @Published var error: String?
    @Published var showsShare = false
    @Published var showsNoCode = false
    @Published var confirmsRevoke = false

    let session: AccountSession
    let roomId: String
    private let api: BipeAPIClient

    init(session: AccountSession, roomId: String, api: BipeAPIClient = BipeAPIClient(baseURL: AppConfig.apiBaseURL)) {
        self.session = session
        self.roomId = roomId
        self.api = api
    }

    private var room: Room? { try? session.client.getRoom(roomId: roomId) }
    private var store: BipeInviteStore { BipeInviteStore(userID: session.userId) }

    /// The text a person sends (bipe_group_link_message, people voice).
    var message: String? {
        guard let url else { return nil }
        let name = room?.displayName()?.nilIfBlank ?? BipeStrings.groupCommonNoRoomName
        return BipeStrings.groupLinkMessage(name, url.absoluteString)
    }

    /// Action appear.
    func appear() async {
        phase = .loading
        do {
            let links = try await withToken { (token: String) async throws(BipeAPIError) -> [BipeGroupLink] in
                try await self.api.groupLinks(roomId: self.roomId, accessToken: token)
            }
            link = links.first
            if let kept = store.groupLink(forRoomID: roomId), kept.id == link?.id {
                url = kept.url
            } else {
                store.removeGroupLink(forRoomID: roomId)
                url = nil
            }
            phase = link == nil ? .none : .active
        } catch {
            fail(error)
        }
    }

    /// Action create: the service account comes in with invite power, then the server makes the link.
    func create() async {
        guard let room else { return }
        phase = .working
        do {
            let service = BipeRegistries.groupsAccountID
            let membership = (try? await room.member(userId: service))?.membership
            if membership != .join && membership != .invite {
                try await room.inviteUserById(userId: service)
            }
            let levels = try await room.getPowerLevels()
            let needed = levels.values().invite
            let current = levels.userPowerLevels()[service] ?? levels.values().usersDefault
            if current < needed {
                try await room.updatePowerLevelsForUsers(updates: [UserPowerLevelUpdate(userId: service, powerLevel: needed)])
            }
        } catch {
            phase = link == nil ? .none : .active
            self.error = BipeStrings.groupLinkErrorAccess
            return
        }
        do {
            let created = try await withToken { (token: String) async throws(BipeAPIError) -> BipeCreatedGroupLink in
                try await self.api.createGroupLink(roomId: self.roomId, accessToken: token)
            }
            store.saveGroupLink(id: created.id, url: created.url, forRoomID: roomId)
            url = created.url
            await appear()
            showsShare = true
        } catch {
            fail(error)
        }
    }

    /// Action share.
    func share() {
        if url == nil { showsNoCode = true } else { showsShare = true }
    }

    /// Action revoke.
    func revoke() async {
        guard let link else { return }
        phase = .working
        do {
            try await withToken { (token: String) async throws(BipeAPIError) in
                try await self.api.revokeGroupLink(id: link.id, accessToken: token)
            }
            store.removeGroupLink(forRoomID: roomId)
            url = nil
            self.link = nil
            phase = .none
        } catch {
            fail(error)
        }
    }

    private func fail(_ error: BipeAPIError) {
        switch error {
        case .server(.notFound, _, _), .server(.groupLinkNoAccess, _, _):
            phase = link == nil ? .none : .active
            self.error = BipeStrings.groupLinkErrorAccess
        default:
            phase = .failed
        }
    }

    private func withToken<T>(_ operation: @MainActor (String) async throws(BipeAPIError) -> T) async throws(BipeAPIError) -> T {
        try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }, operation)
    }
}

/// Spec group-link.
struct GroupLinkView: View {
    @StateObject private var model: GroupLinkModel

    init(session: AccountSession, roomId: String) {
        _model = StateObject(wrappedValue: GroupLinkModel(session: session, roomId: roomId))
    }

    var body: some View {
        List {
            switch model.phase {
            case .loading:
                ProgressView()
            case .failed:
                Section {
                    Text(BipeStrings.errorNetwork)
                    Button(BipeStrings.appRetry) { Task { await model.appear() } }
                }
            case .none, .active, .working:
                if let link = model.link {
                    Section {
                        Text(link.signsUp ? BipeStrings.groupLinkSignsUp(link.signupsLeft.formatted()) : BipeStrings.groupLinkExistingOnly)
                        Text(BipeStrings.groupLinkExpires(link.expiresAt.formatted(date: .abbreviated, time: .omitted)))
                            .foregroundStyle(BipeColor.inkSecondary)
                        Text(BipeStrings.groupLinkJoined(link.joined.formatted()))
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    Section {
                        Button(BipeStrings.groupLinkShare) { model.share() }
                        Button(BipeStrings.groupLinkRevoke, role: .destructive) { model.confirmsRevoke = true }
                    }
                    .disabled(model.phase == .working)
                } else {
                    Section {
                        Text(BipeStrings.groupLinkBody)
                    } footer: {
                        Text(BipeStrings.groupLinkServiceNote)
                    }
                    Section {
                        Button(BipeStrings.groupLinkCreate) { Task { await model.create() } }
                            .disabled(model.phase == .working)
                    }
                }
            }
        }
        .navigationTitle(BipeStrings.groupLinkTitle)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier(BipeScreen.groupLink.rawValue)
        .task { await model.appear() }
        .sheet(isPresented: $model.showsShare) {
            if let message = model.message { ShareSheet(items: [message]) }
        }
        .alert(BipeStrings.groupLinkRevoke, isPresented: $model.confirmsRevoke) {
            Button(BipeStrings.groupLinkRevoke, role: .destructive) { Task { await model.revoke() } }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.groupLinkRevokeBody)
        }
        .alert(BipeStrings.groupLinkNoCode, isPresented: $model.showsNoCode) {
            Button(BipeStrings.groupLinkCreate) { Task { await model.create() } }
            Button(BipeStrings.appCancel, role: .cancel) {}
        }
        .alert(model.error ?? "", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button(BipeStrings.appClose, role: .cancel) {}
        }
    }
}

/// A bipe.chat/g/<code> link (spec group-link-open). Prints redacted: the code is a secret.
struct GroupLinkCode: Identifiable, Equatable, CustomStringConvertible {
    let code: String
    var id: String { code }
    var description: String { "GroupLinkCode(redacted)" }
}

/// Spec group-link-open.
@MainActor
final class GroupLinkOpenModel: ObservableObject {
    enum Phase: Equatable {
        case loading
        case preview
        case joining
        case invalid
        case refused
        case failed
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var preview: BipeGroupLinkPreview?

    private let session: AccountSession
    private let code: String
    private let api: BipeAPIClient

    init(session: AccountSession, code: String, api: BipeAPIClient = BipeAPIClient(baseURL: AppConfig.apiBaseURL)) {
        self.session = session
        self.code = code
        self.api = api
    }

    var name: String { preview?.name?.nilIfBlank ?? BipeStrings.groupCommonNoRoomName }

    /// Action open.
    func open() async {
        phase = .loading
        do {
            let preview = try await api.groupLinkPreview(code: code)
            self.preview = preview
            phase = preview.valid ? .preview : .invalid
        } catch {
            phase = .failed
        }
    }

    /// Action join. Returns the room ID on success: the caller opens the chat.
    func join() async -> String? {
        phase = .joining
        do {
            let roomId = try await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() }) { (token: String) async throws(BipeAPIError) -> String in
                try await self.api.joinGroupLink(code: self.code, accessToken: token)
            }
            _ = try await session.client.joinRoomById(roomId: roomId)
            return roomId
        } catch let error as BipeAPIError {
            switch error {
            case .server(.notFound, _, _): phase = .invalid
            case .server(.groupLinkNoAccess, _, _): phase = .refused
            default: phase = .failed
            }
        } catch {
            phase = .failed
        }
        return nil
    }
}

/// Spec group-link-open.
struct GroupLinkOpenView: View {
    @StateObject private var model: GroupLinkOpenModel
    let openChat: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    init(session: AccountSession, link: GroupLinkCode, openChat: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: GroupLinkOpenModel(session: session, code: link.code))
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
                case .preview, .joining:
                    Text(model.name)
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    if let members = model.preview?.members {
                        Text(BipeStrings.appRoomLinkMembers(String(members)))
                            .foregroundStyle(BipeColor.inkSecondary)
                    }
                    if let inviter = model.preview?.inviterName?.nilIfBlank {
                        Text(BipeStrings.groupLinkOpenInvitedBy(inviter))
                            .foregroundStyle(BipeColor.inkSecondary)
                            .multilineTextAlignment(.center)
                    }
                    Button(BipeStrings.appRoomLinkJoin) {
                        Task {
                            if let roomId = await model.join() {
                                dismiss()
                                openChat(roomId)
                            }
                        }
                    }
                    .buttonStyle(.bipeProminent)
                    .disabled(model.phase == .joining)
                case .invalid:
                    Text(BipeStrings.groupLinkOpenInvalid)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                case .refused:
                    Text(BipeStrings.appRoomLinkInviteOnly)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                case .failed:
                    Text(BipeStrings.errorNetwork)
                        .foregroundStyle(BipeColor.inkPrimary)
                        .multilineTextAlignment(.center)
                    Button(BipeStrings.appRetry) { Task { await model.open() } }
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
        .accessibilityIdentifier(BipeScreen.groupLinkOpen.rawValue)
        .presentationDetents([.medium, .large])
        .task { await model.open() }
    }
}
