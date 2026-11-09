// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation
import MatrixRustSDK

/// Spec registries/synced-settings.json (bipe-chat/bipe#1161): keeps this device's copy and the account's settings
/// together. The copy of the current account applies to the whole app. A failure changes nothing on the server, and
/// the next attach or foreground tries again.
@MainActor
enum SyncedSettings {
    private static var client: Client?
    private static var userId = ""
    private static var defaults: UserDefaults { .standard }

    private static func firstKey(_ userId: String) -> String { "bipe.settings.synced.\(userId)" }
    private static func pendingKey(_ userId: String) -> String { "bipe.settings.syncPending.\(userId)" }

    /// The current account changed.
    static func attach(client: Client, userId: String) {
        self.client = client
        self.userId = userId
        refresh()
    }

    /// Read the account's settings again: when the app comes to the front (ADR 0273), and after a change here.
    static func refresh() {
        guard let client else { return }
        let userId = userId
        Task { await sync(client: client, userId: userId) }
    }

    /// The person changed a setting on this device.
    static func changed(_ key: String) {
        guard client != nil else { return }
        var pending = defaults.stringArray(forKey: pendingKey(userId)) ?? []
        if !pending.contains(key) { pending.append(key) }
        defaults.set(pending, forKey: pendingKey(userId))
        refresh()
    }

    private static func sync(client: Client, userId: String) async {
        let pending = Set(defaults.stringArray(forKey: pendingKey(userId)) ?? [])
        let first = !defaults.bool(forKey: firstKey(userId))
        let local = localValues(userId: userId)
        do {
            let json = try await ServerAccountData.json(client: client, eventType: BipeSyncedSettings.eventType)
            let server = BipeSyncedSettings.parse(json)
            let result = BipeSyncedSettings.reconcile(server: server, local: local, pending: pending, first: first)
            if !BipeSyncedSettings.same(result, server) {
                try await client.setAccountData(eventType: BipeSyncedSettings.eventType, content: BipeSyncedSettings.content(result))
            }
            defaults.set(true, forKey: firstKey(userId))
            let remaining = (defaults.stringArray(forKey: pendingKey(userId)) ?? []).filter { !pending.contains($0) }
            defaults.set(remaining, forKey: pendingKey(userId))
            guard self.client === client else { return }
            apply(result, userId: userId)
        } catch {
            // Offline or refused: this device keeps its copy and its pending changes.
        }
    }

    private static func localValues(userId: String) -> [String: Any] {
        var local: [String: Any] = [:]
        if let on = defaults.object(forKey: BipeSyncedSettings.readReceiptsDefaultsKey) as? Bool { local[BipeSyncedSettings.readReceipts] = on }
        if let id = AppTheme.defaults.string(forKey: AppTheme.paletteKey) { local[BipeSyncedSettings.palette] = id }
        if let id = AppTheme.defaults.string(forKey: AppTheme.chatBackgroundKey) { local[BipeSyncedSettings.chatBackground] = id }
        if let on = defaults.object(forKey: BipeSyncedSettings.muralDefaultsKey(userId: userId)) as? Bool { local[BipeSyncedSettings.mural] = on }
        return local
    }

    /// An unknown id or a wrong type changes nothing.
    private static func apply(_ settings: [String: Any], userId: String) {
        if let on = settings[BipeSyncedSettings.readReceipts] as? Bool { defaults.set(on, forKey: BipeSyncedSettings.readReceiptsDefaultsKey) }
        if let on = settings[BipeSyncedSettings.mural] as? Bool { defaults.set(on, forKey: BipeSyncedSettings.muralDefaultsKey(userId: userId)) }
        if let palette = (settings[BipeSyncedSettings.palette] as? String).flatMap(BipePalette.init(rawValue:)), palette != AppTheme.stored {
            AppTheme.save(palette)
        }
        if let background = (settings[BipeSyncedSettings.chatBackground] as? String).flatMap(BipeChatBackground.init(rawValue:)),
           background != AppTheme.storedChatBackground {
            AppTheme.save(background)
        }
    }
}
