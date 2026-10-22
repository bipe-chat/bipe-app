// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Settings logic of specs settings-root, settings-group, sign-out and data-export (bipe-shared product/screens),
// written from the specs and api/openapi.yaml.

import Foundation

/// Row gates of registries/settings.json (spec settings-group notes): absent → shown; ios or android → that platform
/// only; flag:<name> → shown when that build flag is on; planned:<issue> and any unknown gate → hidden (fail closed).
public enum BipeSettingsGate {
    public static func isShown(_ gate: String?, platform: String, flags: Set<String> = []) -> Bool {
        guard let gate else { return true }
        if gate == "ios" || gate == "android" {
            return gate == platform
        }
        if gate.hasPrefix("flag:") {
            return flags.contains(String(gate.dropFirst("flag:".count)))
        }
        return false
    }
}

public extension BipeSettingsRow {
    func isShownOnIOS(flags: Set<String> = []) -> Bool {
        BipeSettingsGate.isShown(gate, platform: "ios", flags: flags)
    }
}

/// Contract `MessagingSetting.who_can_message`.
public enum BipeWhoCanMessage: String, Codable, Sendable, CaseIterable {
    case everyone
    case verified
    case contacts

    /// The privacy row subtitle of settings-root.
    public var settingsSummary: String {
        switch self {
        case .everyone: BipeStrings.settingsPrivacySummaryEveryone
        case .verified: BipeStrings.settingsPrivacySummaryVerified
        case .contacts: BipeStrings.settingsPrivacySummaryContacts
        }
    }
}

/// One way back into the account after the phone is lost (spec sign-out, type WayBack).
public enum BipeWayBack: String, Sendable, CaseIterable {
    case email
    case linkedAccount
    case recoveryKey
    case syncedToken

    public var title: String {
        switch self {
        case .email: BipeStrings.emailTitle
        case .linkedAccount: BipeStrings.linkedAccountsTitle
        case .recoveryKey: BipeStrings.accountRecoveryKey
        case .syncedToken: BipeStrings.appSignOutSyncedToken
        }
    }
}

/// The ways back of the account (spec settings-root notes): a confirmed recovery email, a linked Apple or Google
/// account (ADR 0096), a recovery key, and the recovery token synced to this phone's platform account.
public struct BipeWaysBack: Sendable, Equatable {
    public let ways: [BipeWayBack]

    public init(hasEmail: Bool, hasLinkedAccount: Bool, hasRecoveryKey: Bool, hasSyncedToken: Bool) {
        var ways: [BipeWayBack] = []
        if hasEmail { ways.append(.email) }
        if hasLinkedAccount { ways.append(.linkedAccount) }
        if hasRecoveryKey { ways.append(.recoveryKey) }
        if hasSyncedToken { ways.append(.syncedToken) }
        self.ways = ways
    }

    public var count: Int { ways.count }

    /// One way back or none: the only warning mark in Settings (ADR 0112).
    public var needsWarning: Bool { count <= 1 }

    /// The account row subtitle of settings-root.
    public var summary: String {
        switch count {
        case 0: BipeStrings.settingsAccountPathsNone
        case 1: BipeStrings.accountPathsWarning
        default: BipeStrings.settingsAccountPaths(count.formatted())
        }
    }

    /// The status card text of settings-group account. `nil` with two ways back or more.
    public var warning: String? {
        switch count {
        case 0: BipeStrings.fallbackReminder
        case 1: BipeStrings.accountPathsWarning
        default: nil
        }
    }
}

/// The file of GET /v1/me/export (spec data-export).
public enum BipeDataExport {
    /// The file name from content-disposition when it is a plain bipe-dados-*.json name, else bipe-dados-<today>.json.
    public static func filename(contentDisposition: String?, today: Date = .now) -> String {
        if let contentDisposition,
           let match = contentDisposition.firstMatch(of: #/filename="?([^";]+)"?/#) {
            let name = String(match.1)
            if name.wholeMatch(of: #/bipe-dados-[A-Za-z0-9_-]+\.json/#) != nil {
                return name
            }
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let parts = calendar.dateComponents([.year, .month, .day], from: today)
        return String(format: "bipe-dados-%04d-%02d-%02d.json", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

// MARK: - API

public extension BipeAPIClient {
    /// `GET /v1/me/messaging`.
    func whoCanMessage(accessToken: String) async throws(BipeAPIError) -> BipeWhoCanMessage {
        let setting: MessagingSetting = try await send(method: "GET", path: ["v1", "me", "messaging"], body: Empty?.none, accessToken: accessToken)
        return setting.whoCanMessage
    }

    /// `PUT /v1/me/messaging`. The server enforces it (ADR 0034).
    func setWhoCanMessage(_ value: BipeWhoCanMessage, accessToken: String) async throws(BipeAPIError) -> BipeWhoCanMessage {
        let setting: MessagingSetting = try await send(method: "PUT", path: ["v1", "me", "messaging"], body: MessagingSetting(whoCanMessage: value), accessToken: accessToken)
        return setting.whoCanMessage
    }

    /// `GET /v1/me/export`. The body as it is, with the file name to save it under.
    func exportMyData(accessToken: String) async throws(BipeAPIError) -> (data: Data, filename: String) {
        let (data, response) = try await perform(method: "GET", path: ["v1", "me", "export"], rawBody: nil, accessToken: accessToken)
        let disposition = response.value(forHTTPHeaderField: "Content-Disposition")
        return (data, BipeDataExport.filename(contentDisposition: disposition))
    }

    /// `POST /v1/me/support`. The room ID of the caller's support room (ADR 0114).
    func supportRoomID(accessToken: String) async throws(BipeAPIError) -> String {
        let room: SupportRoom = try await send(method: "POST", path: ["v1", "me", "support"], body: Empty?.none, accessToken: accessToken)
        return room.roomId
    }
}

private struct MessagingSetting: Codable {
    let whoCanMessage: BipeWhoCanMessage
}

private struct SupportRoom: Decodable {
    let roomId: String
}
