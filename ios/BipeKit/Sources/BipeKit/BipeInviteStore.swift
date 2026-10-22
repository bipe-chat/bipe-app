// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeInviteStore.swift).

import Foundation
import Security

/// On-device memory for personal invites, per account.
/// - Invite links: the server keeps only a hash of each code, so the link exists only where it was created.
///   Links are bearer secrets and live in the keychain.
/// - Names: on the server. Older builds kept them here; see `localNames()`.
/// - Seen joins: the invite IDs whose join the user was already told about. Not secret; UserDefaults.
/// - Notes (ADR 0136): the first message for the person who joins with an invite. Personal text, so in the keychain,
///   this device only. The server never receives a note.
public nonisolated struct BipeInviteStore {
    private let userID: String
    private let defaults: UserDefaults
    
    private static let linksKeychainService = "chat.bipe.invites"
    private static let notesKeychainService = "chat.bipe.invites.notes"
    
    public init(userID: String, defaults: UserDefaults = .standard) {
        self.userID = userID
        self.defaults = defaults
    }
    
    // MARK: - Links
    
    /// Links of invites created on this device, by invite ID.
    public func links() -> [String: URL] {
        guard let data = readKeychain(service: Self.linksKeychainService),
              let strings = try? JSONDecoder().decode([String: String].self, from: data) else { return [:] }
        return strings.compactMapValues(URL.init(string:))
    }
    
    public func saveLink(_ url: URL, forInviteID id: String) {
        var current = links()
        current[id] = url
        writeLinks(current)
    }
    
    /// Drops links whose invite is no longer pending (used, expired or revoked).
    public func keepLinks(forPendingInviteIDs ids: Set<String>) {
        let current = links()
        let kept = current.filter { ids.contains($0.key) }
        if kept.count != current.count {
            writeLinks(kept)
        }
    }
    
    // MARK: - Notes
    
    /// Notes of invites created on this device, by invite ID.
    public func notes() -> [String: String] {
        guard let data = readKeychain(service: Self.notesKeychainService) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
    
    /// Stores the note for an invite. Text with only spaces is no note.
    public func saveNote(_ note: String, forInviteID id: String) {
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var current = notes()
        current[id] = trimmed
        writeNotes(current)
    }
    
    public func removeNote(forInviteID id: String) {
        var current = notes()
        guard current.removeValue(forKey: id) != nil else { return }
        writeNotes(current)
    }
    
    // MARK: - Names
    
    /// Invite names from builds that kept them only on the device, by invite ID. The server keeps names now;
    /// the invites screen uploads these once and then removes them.
    public func localNames() -> [String: String] {
        defaults.dictionary(forKey: namesKey) as? [String: String] ?? [:]
    }
    
    public func removeLocalNames() {
        defaults.removeObject(forKey: namesKey)
    }
    
    // MARK: - Seen joins
    
    /// `nil` until the first check on this device, so the first check can mark old joins as seen without announcing them.
    public func seenJoinedInviteIDs() -> Set<String>? {
        defaults.stringArray(forKey: seenKey).map(Set.init)
    }
    
    public func setSeenJoinedInviteIDs(_ ids: Set<String>) {
        defaults.set(ids.sorted(), forKey: seenKey)
    }
    
    // MARK: - Invite pairs
    
    /// The last known invite pairs (ADR 0034), so trust works before the API answers. Not secret; UserDefaults.
    public func invitePairs() -> Set<String> {
        Set(defaults.stringArray(forKey: pairsKey) ?? [])
    }
    
    public func setInvitePairs(_ userIDs: Set<String>) {
        defaults.set(userIDs.sorted(), forKey: pairsKey)
    }
    
    // MARK: - Inviter greeting
    
    /// The inviter of this account (ADR 0034), until the user sends them a first message.
    /// The chat list and the chat with the inviter show a prompt while it is set. Not secret; UserDefaults.
    public func inviterGreeting() -> BipeInviterGreeting? {
        guard let values = defaults.dictionary(forKey: greetingKey) as? [String: String],
              let userID = values["userID"] else { return nil }
        return BipeInviterGreeting(userID: userID, handle: values["handle"])
    }
    
    public func setInviterGreeting(_ greeting: BipeInviterGreeting?) {
        if let greeting {
            var values = ["userID": greeting.userID]
            values["handle"] = greeting.handle
            defaults.set(values, forKey: greetingKey)
        } else {
            defaults.removeObject(forKey: greetingKey)
        }
        NotificationCenter.default.post(name: .bipeInviterGreetingDidChange, object: userID)
    }
    
    // MARK: - Private
    
    private var seenKey: String {
        "bipe.invites.seenJoined.\(userID)"
    }
    
    private var namesKey: String {
        "bipe.invites.names.\(userID)"
    }
    
    private var pairsKey: String {
        "bipe.invites.pairs.\(userID)"
    }
    
    private var greetingKey: String {
        "bipe.invites.greeting.\(userID)"
    }
    
    private func writeLinks(_ links: [String: URL]) {
        guard let data = try? JSONEncoder().encode(links.mapValues(\.absoluteString)) else { return }
        writeKeychain(data, service: Self.linksKeychainService)
    }
    
    private func writeNotes(_ notes: [String: String]) {
        guard let data = try? JSONEncoder().encode(notes) else { return }
        writeKeychain(data, service: Self.notesKeychainService)
    }
    
    private func keychainQuery(service: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: userID]
    }
    
    private func readKeychain(service: String) -> Data? {
        var query = keychainQuery(service: service)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }
    
    private func writeKeychain(_ data: Data, service: String) {
        let query = keychainQuery(service: service)
        let attributes: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        if SecItemUpdate(query as CFDictionary, attributes as CFDictionary) == errSecItemNotFound {
            SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
        }
    }
}

/// The person who invited this account, by Matrix ID. `handle` comes from the invite preview and can be out of date.
public struct BipeInviterGreeting: Equatable, Sendable {
    public let userID: String
    public let handle: String?
    
    public init(userID: String, handle: String?) {
        self.userID = userID
        self.handle = handle
    }
}

public extension Notification.Name {
    /// Posted with the account's user ID as the object when the inviter greeting changes.
    static let bipeInviterGreetingDidChange = Notification.Name("BipeInviterGreetingDidChange")
}
