// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import IOKit
import MatrixRustSDK

/// The device entries of Aparelhos e assistentes on the Mac (ADR 0291, bipe-shared docs/drafts-room.md "Device
/// entries"): this Mac writes its own entry (source self, kind computer-app) in the drafts room after the link and again
/// on start when a field changed, and reads the entries of the room for the read-only list.
@MainActor
enum MacDeviceEntries {
    private static var writing: Set<String> = []

    /// At each start: writes this Mac's self entry when it was never written or a field changed (a new app version,
    /// system, or the approving phone found in its approval entry). The Mac never creates the drafts room: the phone
    /// that linked it already has one (default chosen in bipe-chat/bipe#1483).
    static func writeSelf(client: Client, userId: String) async {
        guard let deviceId = try? client.deviceId(), let room = await draftsRoom(client: client) else { return }
        let id = "\(userId)|\(deviceId)"
        guard !writing.contains(id) else { return }
        writing.insert(id)
        defer { writing.remove(id) }
        let defaults = UserDefaults.standard
        let linkedKey = "bipe.devices.linkedAt.\(id)"
        let sentKey = "bipe.devices.sent.\(id)"
        // The phone that approved this Mac wrote an approval entry: its linked_by and linked_at carry over.
        let approval = await entries(client: client, userId: userId, room: room)
            .last { $0.deviceId == deviceId && $0.source == "approval" }
        let linkedAt = (defaults.object(forKey: linkedKey) as? NSNumber)?.int64Value
            ?? approval?.linkedAt ?? Int64(Date.now.timeIntervalSince1970 * 1000)
        let entry = BipeDevices.Entry(deviceId: deviceId, source: "self", kind: "computer-app", model: MacModel.name,
                                      os: MacModel.system, app: "Bipe \(MacSettingsText.version)", linkedAt: linkedAt,
                                      linkedBy: approval?.linkedBy, attested: false)
        // The fields, in a fixed order: JSON key order changes between runs.
        let fingerprint = [entry.model, entry.os, entry.app, String(linkedAt), entry.linkedBy].map { $0 ?? "" }
            .joined(separator: "|")
        guard defaults.string(forKey: sentKey) != fingerprint, let content = BipeDevices.content(entry) else { return }
        do {
            try await room.sendRaw(eventType: "m.room.message", content: content)
        } catch {
            return
        }
        defaults.set(NSNumber(value: linkedAt), forKey: linkedKey)
        defaults.set(fingerprint, forKey: sentKey)
    }

    /// Spec linked-devices appear: GET /_matrix/client/v3/devices and the entries of the drafts room, through
    /// device_rows. nil when the device list did not load. A missing drafts room gives rows from server data only.
    static func list(account: MacAccount) async -> BipeDevices.DeviceList? {
        guard let client = account.client, let homeserver = URL(string: client.homeserver()),
              let thisId = try? client.deviceId() else { return nil }
        let matrix = BipeMatrixAccountAPI(homeserver: homeserver)
        guard let devices = try? await account.authorized({ (token: String) async throws(BipeAPIError) -> [BipeMatrixDevice] in
            try await matrix.devices(accessToken: token)
        }) else { return nil }
        let userId = account.userId
        var found: [BipeDevices.Entry] = []
        if let room = await draftsRoom(client: client) {
            found = await entries(client: client, userId: userId, room: room)
        }
        return BipeDevices.rows(thisDeviceId: thisId, devices: devices, entries: found)
    }

    /// The drafts room of the account (account data chat.bipe.drafts_room), when this Mac has joined it.
    static func draftsRoom(client: Client) async -> Room? {
        guard let json = try? await client.accountData(eventType: BipeRegistries.draftsAccountDataType),
              let id = BipeAssistantDrafts.roomId(accountDataJson: json),
              let room = try? client.getRoom(roomId: id), room.membership() == .joined else { return nil }
        return room
    }

    /// The valid device entries of the drafts room, oldest first. Reads the room timeline back to its start, a few
    /// pages at most (the room is small), then closes the timeline.
    private static func entries(client: Client, userId: String, room: Room) async -> [BipeDevices.Entry] {
        guard let timeline = try? await room.timeline() else { return [] }
        let stream = TimelineDiffStream()
        let handle = await timeline.addListener(listener: stream)
        defer { handle.cancel() }
        let collector = Task { @MainActor () -> [TimelineItem] in
            var items: [TimelineItem] = []
            for await diffs in stream.updates {
                if Task.isCancelled { break }
                for diff in diffs { items.apply(diff.vectorDiff) }
            }
            return items
        }
        // The stream has no end, so the collector stops on cancel and returns what it read.
        let reader = Task { @MainActor in
            for _ in 0..<5 {
                guard let hitStart = try? await timeline.paginateBackwards(numEvents: 50), !hitStart else { break }
            }
            try? await Task.sleep(for: .milliseconds(300))
        }
        await reader.value
        collector.cancel()
        let items = await collector.value
        var result: [BipeDevices.Entry] = []
        for item in items {
            guard let event = item.asEvent() else { continue }
            var redacted = false
            if case .msgLike(let content) = event.content, case .redacted = content.kind { redacted = true }
            // An entry counts only when the SDK shows no shield on it: the sending device is verified.
            var verified = false
            if case .none = event.lazyProvider.getShields(strict: false) { verified = true }
            if let entry = BipeDevices.entry(eventJson: event.lazyProvider.debugInfo().originalJson, sender: event.sender,
                                             redacted: redacted, senderVerified: verified, ownUserId: userId) {
                result.append(entry)
            }
        }
        return result
    }
}

/// This Mac for the device entry and the user agent (bipe-shared registries/device-kinds.json userAgents).
enum MacModel {
    /// The marketing name without the part in brackets, for example "MacBook Air" from "MacBook Air (M2, 2022)"
    /// (IODeviceTree product-name on Apple silicon); else the model identifier, for example "Mac14,2".
    static let name: String? = {
        let entry = IORegistryEntryFromPath(kIOMainPortDefault, "IODeviceTree:/product")
        if entry != 0 {
            defer { IOObjectRelease(entry) }
            if let data = IORegistryEntryCreateCFProperty(entry, "product-name" as CFString, kCFAllocatorDefault, 0)?
                .takeRetainedValue() as? Data {
                let full = String(decoding: data, as: UTF8.self).trimmingCharacters(in: CharacterSet(charactersIn: "\0").union(.whitespaces))
                let short = full.components(separatedBy: " (").first?.trimmingCharacters(in: .whitespaces) ?? ""
                if !short.isEmpty { return short }
            }
        }
        var size = 0
        guard sysctlbyname("hw.model", nil, &size, nil, 0) == 0, size > 1 else { return nil }
        var bytes = [CChar](repeating: 0, count: size)
        guard sysctlbyname("hw.model", &bytes, &size, nil, 0) == 0 else { return nil }
        let identifier = String(cString: bytes)
        return identifier.isEmpty ? nil : identifier
    }()

    /// For example "macOS 26.1".
    static var system: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "macOS \(version.majorVersion).\(version.minorVersion)"
    }

    /// "Bipe/1.0.1 (MacBook Air; macOS 26.1)": the rule computer-app of registries/device-kinds.json reads it, so a Mac
    /// with no entry yet still shows its model and system.
    static var userAgent: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let model = (name ?? "Mac").filter { $0.isASCII && ($0.isLetter || $0.isNumber || " ,._-".contains($0)) }
        return "Bipe/\(version) (\(model.isEmpty ? "Mac" : model); \(system))"
    }
}
