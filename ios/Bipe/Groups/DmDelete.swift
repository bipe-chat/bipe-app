// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK
import SwiftUI

/// The cleared-DM markers of the account (spec dm-delete notes). chat-list hides a cleared DM and chat-timeline drops
/// the events at or below its marker. Read from Client.accountData at start and when the app comes to the front.
@MainActor
final class ClearedChatsStore: ObservableObject {
    static let shared = ClearedChatsStore()

    @Published private(set) var markers: [String: UInt64] = [:]
    @Published private(set) var revision: UInt64 = 0
    private var cached: [String: [String: UInt64]] = [:]
    private var pending: [String: [String: UInt64]] = [:]
    private var displayedUserId: String?
    private var refreshVersion: UInt64 = 0

    func refresh(client: Client) async {
        guard let userId = try? client.userId() else { return }
        refreshVersion &+= 1
        let version = refreshVersion
        if displayedUserId != userId {
            displayedUserId = userId
            markers = cached[userId] ?? pending[userId] ?? [:]
        }
        do {
            let loaded = overlay(ClearedChats(json: try await client.accountData(eventType: ClearedChats.eventType)), userId: userId).markers
            let changed = cached[userId] != loaded
            cached[userId] = loaded
            guard version == refreshVersion else { return }
            if markers != loaded { markers = loaded }
            if changed { revision &+= 1 }
        } catch { /* Keep this account's cached markers on a local read error. */ }
    }

    func set(userId: String, roomId: String, marker: UInt64) {
        var accountPending = pending[userId] ?? [:]
        accountPending[roomId] = max(accountPending[roomId] ?? 0, marker)
        pending[userId] = accountPending
        var accountCached = cached[userId] ?? [:]
        accountCached[roomId] = max(accountCached[roomId] ?? 0, marker)
        cached[userId] = accountCached
        if displayedUserId == userId { markers = cached[userId] ?? [:] }
        revision &+= 1
    }

    /// A successful clear takes effect before the SDK receives its account-data echo, only for its owner.
    func overlay(_ stored: ClearedChats, userId: String) -> ClearedChats {
        var result = stored.markers
        for (room, marker) in pending[userId, default: [:]] { result[room] = max(result[room] ?? 0, marker) }
        return ClearedChats(markers: result)
    }

    func hidden(roomId: String, latest: Date?) -> Bool {
        ClearedChats.isHidden(marker: markers[roomId], latestTs: latest.map { UInt64(max(0, $0.timeIntervalSince1970 * 1000)) })
    }

    func keeps(roomId: String, sentAt: Date) -> Bool {
        ClearedChats.keepsEvent(marker: markers[roomId], eventTs: UInt64(max(0, sentAt.timeIntervalSince1970 * 1000)))
    }
}

/// Spec dm-delete (bipe-shared product/screens/dm-delete.json).
@MainActor
final class DmDeleteModel: ObservableObject {
    enum Phase: Equatable {
        case confirm
        case deleting
        case failed
    }

    enum DeleteError: Error {
        case server
    }

    @Published private(set) var phase = Phase.confirm

    let roomId: String
    private let session: AccountSession

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    /// Action delete: read-merge-write of the server copy of chat.bipe.cleared_chats. Returns true on success.
    func delete() async -> Bool {
        guard phase != .deleting, let room = try? session.client.getRoom(roomId: roomId) else { return false }
        phase = .deleting
        do {
            let info = try await room.roomInfo()
            guard info.isDm, info.membership == .joined else { phase = .failed; return false }
            let ts = await Self.latestTs(room)
            let content = ClearedChats.content(clearing: try await serverContent(), roomID: roomId, at: ts)
            let data = try JSONSerialization.data(withJSONObject: content)
            try await session.client.setAccountData(eventType: ClearedChats.eventType, content: String(decoding: data, as: UTF8.self))
            ClearedChatsStore.shared.set(userId: session.userId, roomId: roomId, marker: ts)
            return true
        } catch {
            phase = .failed
            return false
        }
    }

    /// The origin_server_ts of the latest remote event (Room.latestEvent).
    private static func latestTs(_ room: Room) async -> UInt64 {
        switch await room.latestEvent() {
        case .remote(let timestamp, _, _, _, _), .local(let timestamp, _, _, _, _), .remoteInvite(let timestamp, _, _):
            return timestamp
        case .none:
            return UInt64(Date().timeIntervalSince1970 * 1000)
        }
    }

    /// The server copy, not Client.accountData: the SDK copy waits for the next sync, so a write from it could drop a
    /// marker another device just added (bipe-chat/bipe#479). Matrix client-server API GET user account data.
    private func serverContent() async throws -> [String: Any]? {
        let auth = try session.client.session()
        let user = auth.userId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/@:")))
            ?? auth.userId
        guard let url = URL(string: "\(auth.homeserverUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/")))/_matrix/client/v3/user/\(user)/account_data/\(ClearedChats.eventType)") else {
            throw DeleteError.server
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(auth.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        switch (response as? HTTPURLResponse)?.statusCode {
        case 200: return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        case 404: return nil
        default: throw DeleteError.server
        }
    }
}

/// Spec dm-delete: a confirm sheet. DM only (RoomInfo.isDm true).
struct DmDeleteView: View {
    @StateObject private var model: DmDeleteModel
    @Environment(\.dismiss) private var dismiss
    private let onDeleted: () -> Void

    init(session: AccountSession, roomId: String, onDeleted: @escaping () -> Void) {
        _model = StateObject(wrappedValue: DmDeleteModel(session: session, roomId: roomId))
        self.onDeleted = onDeleted
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(BipeStrings.dmDeleteTitle)
                .font(.title3.bold())
                .foregroundStyle(BipeColor.inkPrimary)
            Text(BipeStrings.dmDeleteBody)
                .foregroundStyle(BipeColor.inkSecondary)
            switch model.phase {
            case .deleting:
                HStack(spacing: 8) {
                    ProgressView()
                    Text(BipeStrings.appDmDeleting)
                        .foregroundStyle(BipeColor.inkSecondary)
                }
            case .failed:
                Text(BipeStrings.dmDeleteError)
                    .foregroundStyle(BipeColor.trustDangerInk)
            case .confirm:
                EmptyView()
            }
            Spacer(minLength: 0)
            Button(role: .destructive) {
                Task {
                    if await model.delete() {
                        dismiss()
                        onDeleted()
                    }
                }
            } label: {
                Text(BipeStrings.dmDeleteConfirm).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(BipeColor.trustDangerInk)
            Button {
                dismiss()
            } label: {
                Text(BipeStrings.appCancel).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderless)
        }
        .disabled(model.phase == .deleting)
        .padding(24)
        .background(BipeColor.surfaceBase.ignoresSafeArea())
        .presentationDetents([.medium])
        .accessibilityIdentifier(BipeScreen.dmDelete.rawValue)
    }
}
