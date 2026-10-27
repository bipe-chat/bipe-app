// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Phosphor
import SwiftUI

/// Where the Conversas chat list goes after invite-signed-in: the inviter's chat, or profile-link-open for the @.
enum TabsLink: Equatable {
    case chat(String)
    case profile(String)
    case person(String)
    case queue(String)
}

/// Hands a TabsLink from the app tabs to the Conversas chat list, as NotificationActions does for notifications.
@MainActor
final class TabsLinks: ObservableObject {
    static let shared = TabsLinks()
    @Published var link: TabsLink?
}

/// Spec invite-signed-in (bipe-chat/bipe#460).
@MainActor
final class InviteSignedInModel: ObservableObject {
    enum Phase: Equatable {
        case checking
        case invited
        case notValid
        case failed
    }

    @Published private(set) var phase: Phase = .checking
    @Published private(set) var inviterHandle: String?
    @Published private(set) var inviterName: String?
    /// false when the inviter is an account on this phone: no chat row.
    @Published private(set) var canChat = false

    let code: String
    private let session: AccountSession
    private let api: BipeAPIClient
    private let accountsOnPhone: [String]

    init(code: String, session: AccountSession, api: BipeAPIClient, accountsOnPhone: [String]) {
        self.code = code
        self.session = session
        self.api = api
        self.accountsOnPhone = accountsOnPhone
    }

    /// Action appear. Returns the inviter's chat when one exists: the sheet closes and the chat opens.
    func appear() async -> String? {
        phase = .checking
        let preview: BipeInvitePreview
        do {
            preview = try await api.invitePreview(code: code)
        } catch {
            phase = .failed
            return nil
        }
        guard preview.valid else {
            phase = .notValid
            return nil
        }
        inviterHandle = preview.inviterHandle
        if let handle = preview.inviterHandle {
            inviterName = "@\(handle)"
            let record = try? await BipeAPIClient.withOpenIDRetry(session.openID) { (token: BipeOpenIDToken) async throws(BipeAPIError) -> BipeHandleRecord in
                try await self.api.handle(handle, openID: token)
            }
            if let userId = record?.userId {
                if let name = try? await session.client.getProfile(userId: userId).displayName, !name.isEmpty {
                    inviterName = name
                }
                if !accountsOnPhone.contains(userId), let room = try? session.client.getDmRoom(userId: userId) {
                    return room.id()
                }
                canChat = !accountsOnPhone.contains(userId)
            } else {
                canChat = true
            }
        }
        phase = .invited
        return nil
    }
}

struct InviteSignedInView: View {
    @StateObject private var model: InviteSignedInModel
    let close: () -> Void
    let openChat: (String) -> Void
    let openProfile: (String) -> Void
    let newAccount: (SignupStart) -> Void

    init(code: String, session: AccountSession, api: BipeAPIClient, accountsOnPhone: [String], close: @escaping () -> Void,
         openChat: @escaping (String) -> Void, openProfile: @escaping (String) -> Void,
         newAccount: @escaping (SignupStart) -> Void) {
        _model = StateObject(wrappedValue: InviteSignedInModel(code: code, session: session, api: api, accountsOnPhone: accountsOnPhone))
        self.close = close
        self.openChat = openChat
        self.openProfile = openProfile
        self.newAccount = newAccount
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                switch model.phase {
                case .checking:
                    ProgressView().frame(maxWidth: .infinity)
                case .invited:
                    Text(model.inviterName.map(BipeStrings.inviteSignedInTitle) ?? BipeStrings.inviteSignedInTitleAnonymous)
                        .font(.title2.bold())
                        .foregroundStyle(BipeColor.inkPrimary)
                    Text(BipeStrings.inviteSignedInBody)
                        .foregroundStyle(BipeColor.inkSecondary)
                    if model.canChat, let handle = model.inviterHandle {
                        Button { openProfile(handle) } label: {
                            Text(BipeStrings.inviteSignedInChat).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bipeProminent)
                        .controlSize(.large)
                    }
                    Button {
                        newAccount(SignupStart(inviteId: nil, code: model.code, accountType: .personal,
                                               inviterHandle: model.inviterHandle))
                    } label: {
                        Text(BipeStrings.accountAdd).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bipeBordered)
                    .controlSize(.large)
                case .notValid:
                    Text(BipeStrings.inviteNoLongerValid)
                        .foregroundStyle(BipeColor.inkPrimary)
                case .failed:
                    Text(BipeStrings.errorNetwork)
                        .foregroundStyle(BipeColor.inkPrimary)
                    Button(BipeStrings.appRetry) { Task { await appear() } }
                        .buttonStyle(.bipeProminent)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(BipeColor.surfaceBase.ignoresSafeArea())
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(BipeStrings.appClose, action: close) }
            }
        }
        .tint(BipeColor.accentFill)
        .presentationDetents([.medium])
        .accessibilityIdentifier(BipeScreen.inviteSignedIn.rawValue)
        .task { await appear() }
    }

    private func appear() async {
        if let roomId = await model.appear() {
            openChat(roomId)
        }
    }
}
