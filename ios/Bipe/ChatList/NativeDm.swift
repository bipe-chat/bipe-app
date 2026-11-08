// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The SDK half of a DM that the phone and the Mac share: the rooms of one person, and a new DM (ADR 0141).
@MainActor
enum NativeDm {
    enum CreateError: Error {
        /// The person's who-can-message setting refused the invite (ADR 0038).
        case inviteRefused
        /// M_LIMIT_EXCEEDED: the first-contact budget, or the server's own rate limit (bipe-chat/bipe#846).
        case limitExceeded
    }

    /// Input of the generated ADR 0141 rule. Reads only this account's local rooms.
    /// getDmRooms is local and includes every joined DM for the peer, beyond the loaded/unread/search page.
    static func candidates(peers: Set<String>, client: Client, cleared: ClearedChats) async throws -> [BipeDmCandidate] {
        var result: [BipeDmCandidate] = []
        for peer in peers.sorted() {
            for room in try client.getDmRooms(userId: peer) {
                try Task.checkCancellation()
                let info = try await room.roomInfo()
                guard info.isDm, !info.isSpace, info.membership == .joined else { continue }
                let timestamp: UInt64?
                switch await room.latestEvent() {
                case .remote(let ts, _, _, _, _), .local(let ts, _, _, _, _), .remoteInvite(let ts, _, _): timestamp = ts
                case .none: timestamp = nil
                }
                result.append(BipeDmCandidate(roomId: info.id, peerUserId: peer,
                    latestActivityMs: timestamp.map { Int64(clamping: $0) }, eligible: true,
                    cleared: ClearedChats.isHidden(marker: cleared.marker(for: info.id), latestTs: timestamp)))
            }
        }
        return result
    }

    /// A new DM with the person (spec person-profile notes). Every room is room version 12, where the server refuses
    /// a creator in the users map with a 400, so the map stays empty in the create and in the second write. The second
    /// write sets the DM power levels of registries/dm-power-levels.json. A failed second write leaves a usable DM.
    static func create(with userId: String, client: Client) async throws -> String {
        let powerLevels = PowerLevels(usersDefault: nil, eventsDefault: nil, stateDefault: nil,
                                      ban: nil, kick: nil, redact: nil, invite: nil, notifications: nil,
                                      users: [:],
                                      events: [:])
        let parameters = CreateRoomParameters(name: nil, isEncrypted: true, isDirect: true,
                                              visibility: .private, preset: .trustedPrivateChat,
                                              invite: [userId], powerLevelContentOverride: powerLevels)
        let roomId: String
        do {
            roomId = try await client.createRoom(request: parameters)
        } catch let error as ClientError {
            if case .MatrixApi(kind: .forbidden, code: _, msg: _, details: _) = error {
                throw CreateError.inviteRefused
            }
            if case .MatrixApi(kind: .limitExceeded(retryAfterMs: _), code: _, msg: _, details: _) = error {
                throw CreateError.limitExceeded
            }
            throw error
        }
        await lock(roomId: roomId, client: client)
        return roomId
    }

    /// Room.sendStateEventRaw with the m.room.power_levels content of a new DM (ADR 0141, bipe-chat/bipe#532).
    private static func lock(roomId: String, client: Client) async {
        for attempt in 1...5 {
            if let room = try? client.getRoom(roomId: roomId) {
                _ = try? await room.sendStateEventRaw(eventType: "m.room.power_levels", stateKey: "",
                                                      content: BipeDmPowerLevels.content(userIds: []))
                return
            }
            // The created room reaches the local store with the next sync.
            try? await Task.sleep(nanoseconds: UInt64(attempt) * 300_000_000)
        }
    }
}
