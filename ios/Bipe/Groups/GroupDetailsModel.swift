// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import UIKit

/// Spec group-details (bipe-shared product/screens/group-details.json).
@MainActor
final class GroupDetailsModel: ObservableObject {
    enum Phase: Equatable {
        case viewing
        case editing
        case saving
    }

    /// The photo in the edit form.
    enum PhotoEdit: Equatable {
        case unchanged
        case new(UIImage)
        case removed
    }

    @Published private(set) var name = ""
    @Published private(set) var avatarUrl: String?
    @Published private(set) var description: String?
    @Published private(set) var memberCount = 0
    @Published private(set) var notificationMode: NotifyMode?
    @Published private(set) var canEdit = false
    @Published private(set) var canInvite = false
    /// Spec group-details notes, privacy (bipe#772): can change the join rule.
    @Published private(set) var canChangePrivacy = false
    /// Spec group-link: admins who can change power levels (the server checks level 50 again).
    @Published private(set) var canMakeGroupLink = false
    @Published private(set) var phase = Phase.viewing
    @Published var showsSaveError = false

    @Published var editName = ""
    @Published var editDescription = ""
    @Published var editPhoto = PhotoEdit.unchanged

    let roomId: String
    let session: AccountSession
    private var handle: TaskHandle?

    init(session: AccountSession, roomId: String) {
        self.session = session
        self.roomId = roomId
    }

    deinit {
        handle?.cancel()
    }

    private var room: Room? { try? session.client.getRoom(roomId: roomId) }

    /// Action appear. Also runs when the screen comes back from people or notifications.
    func appear() async {
        guard let room else { return }
        if let info = try? await room.roomInfo() {
            apply(info)
        }
        await refresh()
        // The view task ends when a child screen covers this one; it subscribes again when it comes back.
        handle?.cancel()
        let stream = RoomInfoStream()
        handle = room.subscribeToRoomInfoUpdates(listener: stream)
        for await info in stream.updates {
            apply(info)
        }
    }

    /// Reads the power levels and the notification mode again.
    func refresh() async {
        guard let room else { return }
        if let levels = try? await room.getPowerLevels() {
            canEdit = levels.canOwnUserSendState(stateEvent: .roomName)
            canInvite = levels.canOwnUserInvite()
            canChangePrivacy = levels.canOwnUserSendState(stateEvent: .roomJoinRules)
            canMakeGroupLink = levels.canOwnUserSendState(stateEvent: .roomPowerLevels)
        }
        notificationMode = await ChatNotificationsModel.currentMode(client: session.client, roomId: roomId)
    }

    /// Action edit.
    func edit() {
        guard canEdit, phase == .viewing else { return }
        editName = name
        editDescription = description ?? ""
        editPhoto = .unchanged
        phase = .editing
    }

    /// Action cancelEdit.
    func cancelEdit() {
        phase = .viewing
    }

    var canSave: Bool {
        phase == .editing && !editName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Action save: sends only what changed.
    func save() async {
        guard canSave, let room else { return }
        phase = .saving
        do {
            let newName = editName.trimmingCharacters(in: .whitespacesAndNewlines)
            if newName != name {
                try await room.setName(name: newName)
            }
            let newDescription = editDescription.trimmingCharacters(in: .whitespacesAndNewlines)
            if newDescription != (description ?? "") {
                try await room.setTopic(topic: newDescription)
            }
            switch editPhoto {
            case .unchanged:
                break
            case .new(let image):
                if let data = image.jpegData(compressionQuality: 0.85) {
                    try await room.uploadAvatar(mimeType: "image/jpeg", data: data, mediaInfo: nil)
                }
            case .removed:
                try await room.removeAvatar()
            }
            phase = .viewing
        } catch {
            phase = .editing
            showsSaveError = true
        }
    }

    private func apply(_ info: RoomInfo) {
        name = info.displayName ?? info.id
        avatarUrl = info.avatarUrl
        description = info.topic.flatMap { $0.isEmpty ? nil : $0 }
        memberCount = Int(info.joinedMembersCount)
    }
}
