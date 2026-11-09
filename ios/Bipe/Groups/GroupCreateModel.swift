// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK
import UIKit

/// Spec group-create (bipe-shared product/screens/group-create.json).
@MainActor
final class GroupCreateModel: ObservableObject {
    enum Phase: Equatable {
        case editing
        case creating
        case failed
    }

    @Published private(set) var name = ""
    @Published private(set) var photo: UIImage?
    @Published var people: [Person] = []
    @Published private(set) var phase = Phase.editing
    @Published private(set) var isQuietDefault = false

    let session: AccountSession

    init(session: AccountSession, people: [Person] = []) {
        self.session = session
        self.people = people
    }

    var canCreate: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && phase != .creating
    }

    /// bipe_app_group_create_quiet_footer shows only when the default for groups is quiet.
    func appear() async {
        let settings = await session.client.getNotificationSettings()
        isQuietDefault = await settings.getDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: false) == .mentionsAndKeywordsOnly
    }

    /// Action setName.
    func setName(_ name: String) {
        self.name = name
        if phase == .failed { phase = .editing }
    }

    /// Action choosePhoto, after the system picker.
    func setPhoto(_ image: UIImage?) {
        photo = image
    }

    /// Action removePhoto.
    func removePhoto() {
        photo = nil
    }

    /// Action removePerson.
    func removePerson(_ userId: String) {
        people.removeAll { $0.userId == userId }
    }

    /// Action create. Returns the new room ID, whether every invite went out, and whether the server answered
    /// M_LIMIT_EXCEEDED to one of them (spec group-create reach notice, bipe-chat/bipe#1299).
    func create() async -> (roomId: String, allInvited: Bool, limited: Bool)? {
        guard canCreate else { return nil }
        phase = .creating
        do {
            var avatar: String?
            if let data = photo?.jpegData(compressionQuality: 0.85) {
                avatar = try await session.client.uploadMedia(mimeType: "image/jpeg", data: data, progressWatcher: nil)
            }
            let parameters = CreateRoomParameters(name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                                                  topic: nil,
                                                  isEncrypted: true,
                                                  isDirect: false,
                                                  visibility: .private,
                                                  preset: .privateChat,
                                                  invite: nil,
                                                  avatar: avatar,
                                                  powerLevelContentOverride: GroupPeople.newGroupPowerLevels)
            let roomId = try await session.client.createRoom(request: parameters)
            var allInvited = true
            var limited = false
            // The room reaches the store with the next sync.
            var room = try? session.client.getRoom(roomId: roomId)
            if room == nil {
                room = try? await session.client.awaitRoomRemoteEcho(roomId: roomId)
            }
            if let room {
                // One call each, so one refused invite does not stop the group.
                for person in people {
                    do {
                        try await room.inviteUserById(userId: person.userId)
                    } catch {
                        allInvited = false
                        if case .limited = InviteOutcome(error: error) { limited = true }
                    }
                }
            } else if !people.isEmpty {
                allInvited = false
            }
            phase = .editing
            return (roomId, allInvited, limited)
        } catch {
            phase = .failed
            return nil
        }
    }
}
