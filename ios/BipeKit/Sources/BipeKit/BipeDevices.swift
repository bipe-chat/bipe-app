// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Aparelhos e assistentes (bipe ADR 0291, bipe-chat/bipe#1481; registries/device-kinds.json, bipe-shared
/// docs/drafts-room.md "Device entries"). Wrappers over the bipe-core rules device_info_from_event, user_agent_info and
/// device_rows, which match deviceInfoFromEvent, userAgentInfo and deviceRows in bipe-shared tools/lib/registries.mjs.
public enum BipeDevices {
    /// A valid device entry of the drafts room.
    public struct Entry: Equatable, Sendable {
        public var deviceId: String
        /// "self" or "approval".
        public var source: String
        /// A registry kind key other than "unknown".
        public var kind: String
        public var model: String?
        public var os: String?
        public var app: String?
        /// Milliseconds.
        public var linkedAt: Int64?
        public var linkedBy: String?
        public var attested: Bool
        /// origin_server_ts of the event, 0 for an entry that the app writes.
        public var ts: Int64

        public init(deviceId: String, source: String, kind: String, model: String?, os: String?, app: String?,
                    linkedAt: Int64?, linkedBy: String?, attested: Bool, ts: Int64 = 0) {
            self.deviceId = deviceId
            self.source = source
            self.kind = kind
            self.model = model
            self.os = os
            self.app = app
            self.linkedAt = linkedAt
            self.linkedBy = linkedBy
            self.attested = attested
            self.ts = ts
        }
    }

    /// What a user agent says about a device.
    public struct UserAgent: Equatable, Sendable {
        public let kind: String
        public let app: String?
        public let model: String?
        public let os: String?
    }

    /// Type DeviceRow of spec linked-devices, without the place (GET /v1/web-link/place).
    public struct Row: Equatable, Sendable, Identifiable {
        public let deviceId: String
        public let isThis: Bool
        public let kind: String
        /// A PhosphorIcon raw value.
        public let icon: String
        /// A bipe-shared string key.
        public let label: String
        /// "devices" or "programs".
        public let section: String
        public let leavesBrazil: Bool
        public let name: String?
        public let model: String?
        public let os: String?
        public let app: String?
        public let lastSeen: Int64?
        public let ip: String?
        public let linkedAt: Int64?
        /// The device ID of the approving device.
        public let linkedBy: String?
        public let attested: Bool
        public let warning: Bool
        public let canSignOut: Bool

        public var id: String { deviceId }
        public var lastUsed: Date? { lastSeen.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } }
        public var linkedDate: Date? { linkedAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) } }
        /// The kind label, for example "Navegador".
        public var kindLabel: String { NSLocalizedString(label, tableName: "Bipe", bundle: .module, comment: "") }
        /// The row title: the name, else bipe_linked_devices_unknown_name.
        public var title: String { name ?? BipeStrings.linkedDevicesUnknownName }
    }

    public struct Counts: Equatable, Sendable {
        public let devices: Int
        public let programs: Int
        public let unknown: Int

        /// The subtitle of the group row: bipe_devices_summary, or bipe_devices_summary_unknown when an unknown
        /// device exists and the registry flag unknownWarning is on.
        public var summary: String {
            if unknown > 0 && BipeRegistries.deviceUnknownWarning {
                return BipeStrings.devicesSummaryUnknown(String(unknown))
            }
            return BipeStrings.devicesSummary(String(devices), String(programs))
        }
    }

    /// The rows (this device first, then newest use first) and the counts.
    public struct DeviceList: Equatable, Sendable {
        public let rows: [Row]
        public let counts: Counts
    }

    /// device_info_from_event for the raw JSON of a drafts room event. sender (EventTimelineItem.sender) replaces the
    /// JSON sender when given, redacted marks a redacted timeline item, and senderVerified is false when the SDK shows
    /// a shield on the event. nil when the event is not a valid entry.
    public static func entry(eventJson json: String?, sender: String? = nil, redacted: Bool = false,
                             senderVerified: Bool, ownUserId: String) -> Entry? {
        guard let data = json?.data(using: .utf8),
              var event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        if let sender { event["sender"] = sender }
        if redacted || ((event["unsigned"] as? [String: Any])?["redacted_because"] != nil) { event["redacted"] = true }
        guard let text = CoreJSON.text(event),
              case .entry(let e) = deviceInfoFromEvent(eventJson: text, ownUserId: ownUserId, senderVerified: senderVerified)
        else { return nil }
        return Entry(deviceId: e.deviceId, source: e.source, kind: e.kind, model: e.model, os: e.os, app: e.app,
                     linkedAt: e.linkedAt, linkedBy: e.linkedBy, attested: e.attested, ts: e.ts)
    }

    /// user_agent_info: what a user agent says about a device, nil when no rule matches.
    public static func userAgentInfo(_ userAgent: String) -> UserAgent? {
        BipeCore.userAgentInfo(userAgent: userAgent).map { UserAgent(kind: $0.kind, app: $0.app, model: $0.model, os: $0.os) }
    }

    /// device_rows: the rows of Aparelhos e assistentes from GET /_matrix/client/v3/devices and the valid entries.
    public static func rows(thisDeviceId: String, devices: [BipeMatrixDevice], entries: [Entry]) -> DeviceList {
        let list = deviceRows(
            thisDeviceId: thisDeviceId,
            devices: devices.map { AccountDevice(deviceId: $0.deviceId, displayName: $0.displayName,
                                                 lastSeenTs: $0.lastSeenTs, lastSeenIp: $0.lastSeenIp) },
            entries: entries.map { AccountDeviceEntry(deviceId: $0.deviceId, source: $0.source, kind: $0.kind, model: $0.model,
                                                      os: $0.os, app: $0.app, linkedAt: $0.linkedAt, linkedBy: $0.linkedBy,
                                                      attested: $0.attested, ts: $0.ts) })
        let rows = list.rows.map { r in
            Row(deviceId: r.deviceId, isThis: r.isThis, kind: r.kind, icon: r.icon, label: r.label, section: r.section,
                leavesBrazil: r.leavesBrazil, name: r.name, model: r.model, os: r.os, app: r.app, lastSeen: r.lastSeen,
                ip: r.ip, linkedAt: r.linkedAt, linkedBy: r.linkedBy, attested: r.attested, warning: r.warning,
                canSignOut: r.canSignOut)
        }
        return DeviceList(rows: rows, counts: Counts(devices: Int(list.counts.devices), programs: Int(list.counts.programs),
                                               unknown: Int(list.counts.unknown)))
    }

    /// The content of a device entry for Room.sendRaw with event type m.room.message: an m.notice whose body other
    /// Matrix apps show, and the field chat.bipe.device_info. Empty optional fields are left out.
    public static func content(_ entry: Entry) -> String? {
        var info: [String: Any] = ["device_id": entry.deviceId, "source": entry.source, "kind": entry.kind]
        info["model"] = entry.model
        info["os"] = entry.os
        info["app"] = entry.app
        info["linked_at"] = entry.linkedAt
        info["linked_by"] = entry.linkedBy
        if entry.attested { info["attested"] = true }
        let body = [entry.model ?? entry.app, entry.os].compactMap { $0 }.joined(separator: " · ")
        let content: [String: Any] = [
            "msgtype": BipeRegistries.deviceInfoMsgtype,
            "body": body.isEmpty ? entry.kind : body,
            BipeRegistries.deviceInfoField: info,
        ]
        return CoreJSON.text(content)
    }
}

/// `POST /v1/me/devices/sign-out-others`.
private struct DevicesSignedOut: Decodable {
    let signedOut: Int
}

public extension BipeAPIClient {
    /// `DELETE /v1/me/devices/{deviceId}` (ADR 0291): signs out one other device of the account. The phone runs the
    /// biometric check first.
    func signOutDevice(_ deviceId: String, accessToken: String) async throws(BipeAPIError) {
        let _: NoContent = try await send(method: "DELETE", path: ["v1", "me", "devices", deviceId], body: Empty?.none,
                                          accessToken: accessToken)
    }

    /// `POST /v1/me/devices/sign-out-others` (ADR 0291): signs out every device of the account except this one.
    /// Returns the number of devices signed out.
    @discardableResult
    func signOutOtherDevices(accessToken: String) async throws(BipeAPIError) -> Int {
        let result: DevicesSignedOut = try await send(method: "POST", path: ["v1", "me", "devices", "sign-out-others"],
                                                      body: Empty?.none, accessToken: accessToken)
        return result.signedOut
    }
}
