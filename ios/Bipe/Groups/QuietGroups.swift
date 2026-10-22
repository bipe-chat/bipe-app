// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import MatrixRustSDK

/// Quiet groups by default (ADR 0026, specs group-create and chat-notifications notes). Once per account, after the
/// first sync: the account default for groups becomes mentions and keywords, and the override rule
/// chat.bipe.poll_start makes a new poll notify in a quiet group.
enum QuietGroups {
    static let pollRuleId = "chat.bipe.poll_start"

    private static func doneKey(_ userId: String) -> String { "bipe.quietGroups.\(userId)" }

    static func applyOnce(session: AccountSession) async {
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: doneKey(session.userId)) else { return }
        let settings = await session.client.getNotificationSettings()
        // The push rules arrive with the first sync.
        var rules: String?
        for _ in 0..<10 {
            rules = try? await settings.getRawPushRules()
            if rules != nil || Task.isCancelled { break }
            try? await Task.sleep(for: .seconds(3))
        }
        guard let rules else { return }
        do {
            if !rules.contains(pollRuleId) {
                try await settings.setCustomPushRule(ruleId: pollRuleId,
                                                     ruleKind: .override,
                                                     actions: [.notify, .setTweak(value: .sound(value: "default"))],
                                                     conditions: [.eventMatch(key: "type", pattern: "*.poll.start")])
            }
            try await settings.setDefaultRoomNotificationMode(isEncrypted: false, isOneToOne: false, mode: .mentionsAndKeywordsOnly)
            // Mentions-only in an encrypted group needs the server to push encrypted events (#118). Until it does,
            // the encrypted default stays as it is and the next start tries again.
            guard await settings.canHomeserverPushEncryptedEventToDevice() else { return }
            try await settings.setDefaultRoomNotificationMode(isEncrypted: true, isOneToOne: false, mode: .mentionsAndKeywordsOnly)
            defaults.set(true, forKey: doneKey(session.userId))
        } catch {
            // Tried again at the next start.
        }
    }
}
