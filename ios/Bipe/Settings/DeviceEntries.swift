// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// The device entries of Aparelhos e assistentes (ADR 0291, bipe-shared docs/drafts-room.md "Device entries"): this
/// phone writes its own entry (source self) in the drafts room after the link and again when a field changed, and an
/// approval entry for a device that it approved with Link a computer. AssistantDraftsStore reads the entries.
@MainActor
enum DeviceEntries {
    private static var writing: Set<String> = []

    /// At start and on each return to the front: writes this phone's self entry when it was never written or a field
    /// changed (a new app version or system). Creates the drafts room when the account has none.
    static func writeSelf(session: AccountSession) async {
        guard let deviceId = try? session.client.deviceId() else { return }
        let id = "\(session.userId)|\(deviceId)"
        guard !writing.contains(id) else { return }
        writing.insert(id)
        defer { writing.remove(id) }
        let defaults = UserDefaults.standard
        let linkedKey = "bipe.devices.linkedAt.\(id)"
        let sentKey = "bipe.devices.sent.\(id)"
        let linkedAt = (defaults.object(forKey: linkedKey) as? NSNumber)?.int64Value ?? Date.now.milliseconds
        let system = ProcessInfo.processInfo.operatingSystemVersion
        let entry = BipeDevices.Entry(deviceId: deviceId, source: "self", kind: "phone", model: PhoneModel.identifier,
                                      os: "iOS \(system.majorVersion).\(system.minorVersion)",
                                      app: "Bipe \(AppVersion.nameAndBuild)", linkedAt: linkedAt, linkedBy: nil,
                                      attested: false)
        // The fields, in a fixed order: JSON key order changes between runs.
        let fingerprint = [entry.model, entry.os, entry.app, String(linkedAt)].map { $0 ?? "" }.joined(separator: "|")
        guard defaults.string(forKey: sentKey) != fingerprint, let content = BipeDevices.content(entry),
              let room = await draftsRoom(session: session) else { return }
        do {
            try await room.sendRaw(eventType: "m.room.message", content: content)
        } catch {
            return
        }
        defaults.set(NSNumber(value: linkedAt), forKey: linkedKey)
        defaults.set(fingerprint, forKey: sentKey)
    }

    /// Spec linked-devices appear: GET /_matrix/client/v3/devices and the entries of the drafts room, through
    /// device_rows. nil when the list did not load.
    static func list(session: AccountSession) async -> BipeDevices.DeviceList? {
        guard let homeserver = URL(string: session.client.homeserver()), let token = session.accessToken(),
              let thisId = try? session.client.deviceId(),
              let devices = try? await BipeMatrixAccountAPI(homeserver: homeserver).devices(accessToken: token) else { return nil }
        let store = AssistantDraftsStore.shared
        let entries = store.userId == session.userId ? store.deviceEntries : []
        return BipeDevices.rows(thisDeviceId: thisId, devices: devices, entries: entries)
    }

    /// The device IDs of the account before an approval, so the new device can be found after it.
    static func deviceIds(session: AccountSession) async -> Set<String>? {
        guard let homeserver = URL(string: session.client.homeserver()), let token = session.accessToken(),
              let devices = try? await BipeMatrixAccountAPI(homeserver: homeserver).devices(accessToken: token) else { return nil }
        return Set(devices.map(\.deviceId))
    }

    /// After Link a computer approved a device: an approval entry from the user agent of the MAS consent page
    /// (BipeDevices.userAgentInfo). The new device is the one device that was not in the list before; the list is
    /// read a few times while the device signs in. Nothing is written when no rule knows the user agent or when more
    /// than one new device shows.
    static func writeApproval(session: AccountSession, userAgent: String?, before: Set<String>) async {
        guard let userAgent, let info = BipeDevices.userAgentInfo(userAgent),
              let ownId = try? session.client.deviceId(),
              let homeserver = URL(string: session.client.homeserver()) else { return }
        let matrix = BipeMatrixAccountAPI(homeserver: homeserver)
        for _ in 0..<10 {
            try? await Task.sleep(for: .seconds(3))
            guard let token = session.accessToken(), let devices = try? await matrix.devices(accessToken: token) else { continue }
            let new = devices.filter { !before.contains($0.deviceId) && $0.deviceId != ownId }
            if new.count > 1 { return }
            guard let device = new.first else { continue }
            let entry = BipeDevices.Entry(deviceId: device.deviceId, source: "approval", kind: info.kind, model: info.model,
                                          os: info.os, app: info.app, linkedAt: Date.now.milliseconds, linkedBy: ownId,
                                          attested: false)
            guard let content = BipeDevices.content(entry), let room = await draftsRoom(session: session) else { return }
            try? await room.sendRaw(eventType: "m.room.message", content: content)
            return
        }
    }

    /// The drafts room of the account. A writer that finds none creates it (bipe-shared docs/drafts-room.md "The
    /// room"): encrypted, invite only, history from the join, muted, then the account data chat.bipe.drafts_room.
    private static func draftsRoom(session: AccountSession) async -> Room? {
        // A failed read must never count as "no drafts room yet".
        let json: String?
        do {
            json = try await session.client.accountData(eventType: BipeRegistries.draftsAccountDataType)
        } catch {
            return nil
        }
        let pendingKey = "bipe.devices.pendingDraftsRoom.\(session.userId)"
        let pending = UserDefaults.standard.string(forKey: pendingKey)
        if let id = BipeAssistantDrafts.roomId(accountDataJson: json), id != pending {
            guard let room = try? session.client.getRoom(roomId: id), room.membership() == .joined else { return nil }
            return room
        }
        // Account data that names no valid room: never make a second room over it.
        if json != nil, BipeAssistantDrafts.roomId(accountDataJson: json) == nil { return nil }
        do {
            let id: String
            if let pending {
                id = pending
            } else {
                id = try await session.client.createRoom(request: CreateRoomParameters(
                    name: BipeStrings.appDraftsTitle, isEncrypted: true, isDirect: false, visibility: .private,
                    preset: .privateChat, invite: nil, joinRuleOverride: .invite, historyVisibilityOverride: .joined))
                // If the account data write fails, the next try finishes it instead of creating another room.
                UserDefaults.standard.set(id, forKey: pendingKey)
            }
            let data = try JSONSerialization.data(withJSONObject: ["room_id": id])
            try await session.client.setAccountData(eventType: BipeRegistries.draftsAccountDataType,
                                                    content: String(decoding: data, as: UTF8.self))
            UserDefaults.standard.removeObject(forKey: pendingKey)
            let room = try await session.client.awaitRoomRemoteEcho(roomId: id)
            let settings = await session.client.getNotificationSettings()
            try? await settings.setRoomNotificationMode(roomId: id, mode: .mute)
            await AssistantDraftsStore.shared.refresh(session: session)
            return room
        } catch {
            return nil
        }
    }
}

private extension Date {
    var milliseconds: Int64 { Int64(timeIntervalSince1970 * 1000) }
}
