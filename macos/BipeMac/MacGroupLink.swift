// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import AppKit
import BipeKit
import MatrixRustSDK
import SwiftUI

/// A bipe.chat/g/<code> link tapped in a message waits here until the conversations view shows group-link-open.
/// The code is a secret: it is never logged.
@MainActor
final class MacGroupLinks: ObservableObject {
    static let shared = MacGroupLinks()
    struct Pending: Identifiable, Equatable { let code: String; var id: String { code } }
    @Published var pending: Pending?

    /// `true` when `url` is a group link, which then opens in the app.
    func offer(_ url: URL) -> Bool {
        guard let code = BipeInviteLink.groupCode(from: url) else { return false }
        pending = Pending(code: code)
        return true
    }
}

private func macNonBlank(_ text: String?) -> String? {
    guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    return text
}

/// Spec group-link (ADR 0184): the admin's link for one group, with share, copy, QR code and revoke.
/// The Mac keeps the link URL for this session only; after a restart, share asks for a new link (bipe_group_link_no_code).
struct MacGroupLinkSheet: View {
    @ObservedObject var account: MacAccount
    let room: Room
    let close: () -> Void

    private enum Phase: Equatable { case loading, none, active, working, failed }
    @State private var phase = Phase.loading
    @State private var link: BipeGroupLink?
    @State private var error: String?
    @State private var showsQr = false
    @State private var confirmsRevoke = false
    @State private var copied = false

    private var roomId: String { room.id() }
    private var url: URL? { account.groupLinkURLs[roomId].flatMap { $0.id == link?.id ? $0.url : nil } }
    private var message: String? {
        url.map { BipeStrings.groupLinkMessage(macNonBlank(room.displayName()) ?? BipeStrings.groupCommonNoRoomName, $0.absoluteString) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            Text(BipeStrings.groupLinkTitle).macFont(.headline)
            switch phase {
            case .loading:
                ProgressView().frame(maxWidth: .infinity)
            case .failed:
                Text(BipeStrings.errorNetwork).macFont(.body)
                Button(BipeStrings.appRetry) { Task { await appear() } }
            case .none, .active, .working:
                if let link {
                    Text(link.signsUp ? BipeStrings.groupLinkSignsUp(link.signupsLeft.formatted()) : BipeStrings.groupLinkExistingOnly)
                        .macFont(.body).fixedSize(horizontal: false, vertical: true)
                    Text(BipeStrings.groupLinkExpires(link.expiresAt.formatted(date: .abbreviated, time: .omitted)))
                        .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    Text(BipeStrings.groupLinkJoined(link.joined.formatted()))
                        .macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    if let url, let message {
                        HStack {
                            ShareLink(item: message) { Text(BipeStrings.groupLinkShare) }
                            Button(BipeStrings.groupLinkCopy) {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(url.absoluteString, forType: .string)
                                copied = true
                            }
                            Button(BipeStrings.groupLinkQr) { showsQr.toggle() }
                        }
                        if copied { Text(BipeStrings.appCopied).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary) }
                        if showsQr, let image = MacQRCode.image(Data(url.absoluteString.utf8)) {
                            VStack(spacing: 8) {
                                Image(nsImage: image).interpolation(.none).resizable().scaledToFit()
                                    .frame(width: 220, height: 220)
                                    .accessibilityLabel(url.absoluteString)
                                Text(BipeStrings.groupLinkQrFooter).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                                    .multilineTextAlignment(.center)
                            }.frame(maxWidth: .infinity)
                        }
                    } else {
                        Text(BipeStrings.groupLinkNoCode).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Button(BipeStrings.groupLinkCreate) { Task { await create() } }
                    }
                    Button(BipeStrings.groupLinkRevoke, role: .destructive) { confirmsRevoke = true }
                } else {
                    Text(BipeStrings.groupLinkBody).macFont(.body).fixedSize(horizontal: false, vertical: true)
                    Text(BipeStrings.groupLinkServiceNote).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button(BipeStrings.groupLinkCreate) { Task { await create() } }
                }
            }
            if let error { MacNotice(text: error, danger: true) }
            HStack {
                Spacer()
                if phase == .working { ProgressView() }
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
            }
        }
        .disabled(phase == .working)
        .padding(MacDesign.gap * 2).frame(width: 420)
        .task { await appear() }
        .confirmationDialog(BipeStrings.groupLinkRevoke, isPresented: $confirmsRevoke) {
            Button(BipeStrings.groupLinkRevoke, role: .destructive) { Task { await revoke() } }
            Button(BipeStrings.appCancel, role: .cancel) {}
        } message: {
            Text(BipeStrings.groupLinkRevokeBody)
        }
    }

    /// Action appear: this admin's own link first, else the newest of another admin.
    private func appear() async {
        phase = .loading
        do {
            let links = try await account.authorized { (token: String) async throws(BipeAPIError) -> [BipeGroupLink] in
                try await account.api.groupLinks(roomId: roomId, accessToken: token)
            }
            link = links.first { $0.createdBy == account.userId } ?? links.first
            phase = link == nil ? .none : .active
        } catch {
            fail(error)
        }
    }

    /// Action create: the service account comes in with invite power, then the server makes the link.
    private func create() async {
        phase = .working
        error = nil
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
            let created = try await account.authorized { (token: String) async throws(BipeAPIError) -> BipeCreatedGroupLink in
                try await account.api.createGroupLink(roomId: roomId, accessToken: token)
            }
            account.groupLinkURLs[roomId] = (created.id, created.url)
            await appear()
        } catch {
            fail(error)
        }
    }

    /// Action revoke.
    private func revoke() async {
        guard let link else { return }
        phase = .working
        do {
            try await account.authorized { (token: String) async throws(BipeAPIError) -> Void in
                try await account.api.revokeGroupLink(id: link.id, accessToken: token)
            }
            account.groupLinkURLs[roomId] = nil
            self.link = nil
            showsQr = false
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
}

/// Spec group-link-open: the group of a bipe.chat/g/<code> link, joined with one tap.
struct MacGroupLinkOpenSheet: View {
    @ObservedObject var account: MacAccount
    let code: String
    let opened: (String) -> Void
    let close: () -> Void

    private enum Phase: Equatable { case loading, preview, joining, invalid, refused, full, failed }
    @State private var phase = Phase.loading
    @State private var preview: BipeGroupLinkPreview?

    private var name: String { macNonBlank(preview?.name) ?? BipeStrings.groupCommonNoRoomName }

    var body: some View {
        VStack(alignment: .leading, spacing: MacDesign.gap) {
            switch phase {
            case .loading:
                ProgressView()
                Text(BipeStrings.appRoomLinkOpening).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
            case .preview, .joining:
                Text(name).macFont(.headline)
                if let members = preview?.members {
                    Text(BipeStrings.appRoomLinkMembers(String(members))).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
                }
                if let inviter = macNonBlank(preview?.inviterName) {
                    Text(BipeStrings.groupLinkOpenInvitedBy(inviter)).macFont(.body).foregroundStyle(BipeColor.inkSecondary)
                }
                Text(BipeStrings.groupLinkOpenHistory).macFont(.footnote).foregroundStyle(BipeColor.inkSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            case .invalid:
                Text(BipeStrings.groupLinkOpenInvalid).macFont(.body)
            case .refused:
                Text(BipeStrings.appRoomLinkInviteOnly).macFont(.body)
            case .full:
                Text(name).macFont(.headline)
                Text(BipeStrings.groupLinkFull(String(BipeRegistries.groupMaxMembers))).macFont(.body)
            case .failed:
                Text(BipeStrings.errorNetwork).macFont(.body)
            }
            HStack {
                Spacer()
                if phase == .joining { ProgressView() }
                Button(BipeStrings.appClose, action: close).keyboardShortcut(.cancelAction)
                if phase == .failed {
                    Button(BipeStrings.appRetry) { Task { await open() } }
                }
                if phase == .preview || phase == .joining {
                    Button(BipeStrings.appRoomLinkJoin) { Task { await join() } }
                        .keyboardShortcut(.defaultAction)
                        .disabled(phase == .joining)
                }
            }
        }
        .padding(MacDesign.gap * 2).frame(width: 420)
        .task { await open() }
    }

    /// Action open.
    private func open() async {
        phase = .loading
        do {
            let found = try await account.api.groupLinkPreview(code: code)
            preview = found
            phase = !found.valid ? .invalid : found.full == true ? .full : .preview
        } catch {
            phase = .failed
        }
    }

    /// Action join: the service account invites, then the Mac joins the room and opens it.
    private func join() async {
        guard phase == .preview, let client = account.client else { return }
        phase = .joining
        do {
            let roomId = try await account.authorized { (token: String) async throws(BipeAPIError) -> String in
                try await account.api.joinGroupLink(code: code, accessToken: token)
            }
            _ = try await client.joinRoomById(roomId: roomId)
            opened(roomId)
        } catch let error as BipeAPIError {
            switch error {
            case .server(.notFound, _, _): phase = .invalid
            case .server(.groupLinkNoAccess, _, _): phase = .refused
            case .server(.groupFull, _, _): phase = .full
            default: phase = .failed
            }
        } catch {
            phase = .failed
        }
    }
}
