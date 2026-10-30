// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
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
        isQuietDefault = await settings.getDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: false) == .mentionsAndKeywordsOnly
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

    /// The power_level_content_override of a new group (ADR 0210, bipe-chat/bipe#879; registries/dm-power-levels.json
    /// groupEvents): every member may set their own assistant marker (chat.bipe.assistant at 0). Synapse replaces its
    /// whole default events map with the override's, so groupEvents repeats the Synapse defaults. users stays empty,
    /// so the creator keeps the server's default level; other fields keep the preset.
    static var groupPowerLevels: PowerLevels {
        PowerLevels(usersDefault: nil, eventsDefault: nil, stateDefault: nil,
                    ban: nil, kick: nil, redact: nil, invite: nil, notifications: nil,
                    users: [:],
                    events: BipeDmPowerLevels.groupEvents)
    }

    /// Action create. Returns the new room ID and whether every invite went out.
    func create() async -> (roomId: String, allInvited: Bool)? {
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
                                                  powerLevelContentOverride: Self.groupPowerLevels)
            let roomId = try await session.client.createRoom(request: parameters)
            var allInvited = true
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
                    }
                }
            } else if !people.isEmpty {
                allInvited = false
            }
            phase = .editing
            return (roomId, allInvited)
        } catch {
            phase = .failed
            return nil
        }
    }
}
