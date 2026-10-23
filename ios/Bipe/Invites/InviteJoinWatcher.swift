// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The inviter side of specs invites and invite-note (ADR 0136), at app start and on each return to the foreground:
/// sends the note waiting for a used invite into the DM with the person who joined, then deletes it from this phone,
/// and shows bipe_invites_joined once for a new join.
@MainActor
final class InviteJoinWatcher: ObservableObject {
    /// The join toast.
    @Published var toast: String?

    private let session: AccountSession
    private let api: BipeAPIClient
    private var checking = false

    init(session: AccountSession, api: BipeAPIClient) {
        self.session = session
        self.api = api
    }

    func check() async {
        guard !checking else { return }
        checking = true
        defer { checking = false }
        // GET /v1/me/invites: each used invite names who joined, which ties a note (kept by invite ID) to the person.
        guard let mine = try? await BipeAPIClient.withTokenRetry(accessToken: { self.session.accessToken() },
                                                                 { (token: String) async throws(BipeAPIError) -> BipeMyInvites in
                                                                     try await self.api.myInvites(accessToken: token)
                                                                 }) else { return }
        let store = BipeInviteStore(userID: session.userId)
        let used = mine.invites.filter { $0.status == .used && $0.joinedUserId != nil }
        let notes = store.notes()
        for invite in used {
            guard let note = notes[invite.id], let userId = invite.joinedUserId else { continue }
            // Without a DM yet, the note waits for the next check.
            if await send(note, to: userId) {
                store.removeNote(forInviteID: invite.id)
            }
        }
        let usedIDs = Set(used.map(\.id))
        guard let seen = store.seenJoinedInviteIDs() else {
            // The first check on this phone marks the old joins as seen without announcing them.
            store.setSeenJoinedInviteIDs(usedIDs)
            return
        }
        store.setSeenJoinedInviteIDs(seen.union(usedIDs))
        if let userId = used.first(where: { !seen.contains($0.id) })?.joinedUserId {
            toast = BipeStrings.invitesJoined(await name(of: userId))
        }
    }

    /// Client.getDmRoom, Room.timeline and Timeline.send: the note as a normal end-to-end encrypted text message.
    private func send(_ note: String, to userId: String) async -> Bool {
        guard let room = try? session.client.getDmRoom(userId: userId),
              let timeline = try? await room.timeline(),
              let content = try? messageEventContentNew(msgtype: .text(content: TextMessageContent(body: note, formatted: nil)))
        else { return false }
        do {
            _ = try await timeline.send(msg: content)
            return true
        } catch {
            return false
        }
    }

    private func name(of userId: String) async -> String {
        let profileName = (try? await session.client.getProfile(userId: userId))?.displayName?
            .trimmingCharacters(in: .whitespaces)
        if let profileName, !profileName.isEmpty, profileName != userId { return profileName }
        return Person.localpart(of: userId)
    }
}
