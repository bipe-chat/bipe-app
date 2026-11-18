// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Combine
import Foundation
import MatrixRustSDK

/// Spec group-leave (bipe-shared product/screens/group-leave.json).
/// Also compiled into the Mac app (macos/project.yml): keep it free of app-only types.
@MainActor
final class GroupLeaveModel: ObservableObject {
    enum Warning: Equatable {
        case none
        case alone
        case lastAdmin
    }

    enum Phase: Equatable {
        case confirm
        case leaving
        case failed
    }

    @Published private(set) var warning = Warning.none
    @Published private(set) var phase = Phase.confirm

    let roomId: String
    private let client: Client
    private let ownUserId: String

    init(client: Client, ownUserId: String, roomId: String) {
        self.client = client
        self.ownUserId = ownUserId
        self.roomId = roomId
    }

    private var room: Room? { try? client.getRoom(roomId: roomId) }

    /// Action appear: reads RoomInfo and the members' roles to set warning.
    func appear() async {
        guard let room, let info = try? await room.roomInfo() else { return }
        if info.joinedMembersCount <= 1 {
            warning = .alone
            return
        }
        guard let members = await GroupPeople.members(of: room, cachedOnly: true) else { return }
        let admins = members.filter { $0.membership == .join && GroupPeople.isAdmin($0) }
        if admins.count == 1, admins.first?.userId == ownUserId {
            warning = .lastAdmin
        }
    }

    /// Action leave. Returns true when the user left.
    func leave() async -> Bool {
        guard let room, phase != .leaving else { return false }
        phase = .leaving
        do {
            try await room.leave()
            return true
        } catch {
            phase = .failed
            return false
        }
    }
}
