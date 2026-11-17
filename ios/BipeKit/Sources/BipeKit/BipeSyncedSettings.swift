// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

// Settings that follow the account (bipe-chat/bipe#1161; bipe-shared registries/synced-settings.json). Global account
// data BipeRegistries.syncedSettingsEventType. Port of syncedSettingsReconcile in bipe-shared tools/lib/registries.mjs.
// Same rules as android/libraries/bipe shared/BipeSyncedSettings.kt.
public enum BipeSyncedSettings {
    public static let eventType = BipeRegistries.syncedSettingsEventType
    public static let readReceipts = "readReceipts"
    public static let palette = "palette"
    public static let chatBackground = "chatBackground"
    public static let mural = "mural"
    /// The switch in this device's defaults, on every Apple client.
    public static let readReceiptsDefaultsKey = "bipe.settings.readReceipts"
    public static func muralDefaultsKey(userId: String) -> String { "bipe.chats.mural.\(userId)" }

    /// The event content, with every key. Empty on nil or malformed content.
    public static func parse(_ json: String?) -> [String: Any] {
        guard let data = json?.data(using: .utf8),
              let content = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return content
    }

    /// The account's settings after this device reads them. See registries/synced-settings.json.
    /// bipe-core `syncedSettingsReconcile`.
    public static func reconcile(server: [String: Any], local: [String: Any], pending: Set<String>, first: Bool) -> [String: Any] {
        let json = syncedSettingsReconcile(serverJson: CoreJSON.text(server), localJson: CoreJSON.text(local) ?? "{}",
                                           pending: pending.sorted(), first: first)
        return CoreJSON.object(json) ?? server
    }

    public static func same(_ a: [String: Any], _ b: [String: Any]) -> Bool {
        NSDictionary(dictionary: a).isEqual(to: b)
    }

    public static func content(_ settings: [String: Any]) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: settings, options: [.sortedKeys])) ?? Data("{}".utf8)
        return String(decoding: data, as: UTF8.self)
    }
}
