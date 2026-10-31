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
    private let ownedAccessGroup: String?
    private let legacyReadGroups: [String]
    private let keychain: any CredentialKeychain
    private static let mutationLock = NSRecursiveLock()
    private enum StorageError: Error { case unavailable }
    
    private static let linksKeychainService = "chat.bipe.invites"
    private static let notesKeychainService = "chat.bipe.invites.notes"
    private static let groupLinksKeychainService = "chat.bipe.invites.groups"
    /// The services the app-group move copies (bipe-chat/bipe#531).
    public static let keychainServices = [linksKeychainService, notesKeychainService, groupLinksKeychainService]
    
    public init(userID: String, defaults: UserDefaults = .standard) {
        self.init(userID: userID, defaults: defaults,
                  ownedAccessGroup: Bundle.main.object(forInfoDictionaryKey: "BipeOwnedKeychainGroup") as? String,
                  legacyReadGroups: Bundle.main.object(forInfoDictionaryKey: "BipeLegacyKeychainReadGroups") as? [String] ?? [],
                  keychain: SystemCredentialKeychain())
    }

    init(userID: String, defaults: UserDefaults, ownedAccessGroup: String?, legacyReadGroups: [String], keychain: any CredentialKeychain) {
        self.userID = userID
        self.defaults = defaults
        self.ownedAccessGroup = ownedAccessGroup.flatMap { !$0.isEmpty && !$0.contains("$(") ? $0 : nil }
        self.legacyReadGroups = legacyReadGroups.filter { !$0.isEmpty && !$0.contains("$(") && $0 != ownedAccessGroup }
        self.keychain = keychain
    }
    
    // MARK: - Links
    
    /// Links of invites created on this device, by invite ID.
    public func links() -> [String: URL] {
        ((try? readStrings(service: Self.linksKeychainService)) ?? [:]).compactMapValues(URL.init(string:))
    }

    @discardableResult
    public func saveLink(_ url: URL, forInviteID id: String) -> Bool {
        mutate(service: Self.linksKeychainService) { $0[id] = url.absoluteString }
    }

    /// A full own-group snapshot, including an empty map, prevents a removed legacy link from returning.
    @discardableResult
    public func keepLinks(forPendingInviteIDs ids: Set<String>) -> Bool {
        mutate(service: Self.linksKeychainService) { $0 = $0.filter { ids.contains($0.key) } }
    }

    // MARK: - Group links (ADR 0184)

    /// The group link made on this device for a room: its ID and URL. The server keeps only a hash of the code.
    public func groupLink(forRoomID roomID: String) -> (id: String, url: URL)? {
        guard let value = (try? readStrings(service: Self.groupLinksKeychainService))?[roomID] else { return nil }
        let parts = value.split(separator: " ", maxSplits: 1).map(String.init)
        guard parts.count == 2, let url = URL(string: parts[1]) else { return nil }
        return (parts[0], url)
    }

    @discardableResult
    public func saveGroupLink(id: String, url: URL, forRoomID roomID: String) -> Bool {
        mutate(service: Self.groupLinksKeychainService) { $0[roomID] = "\(id) \(url.absoluteString)" }
    }

    @discardableResult
    public func removeGroupLink(forRoomID roomID: String) -> Bool {
        mutate(service: Self.groupLinksKeychainService) { $0[roomID] = nil }
    }

    // MARK: - Notes

    public func notes() -> [String: String] {
        (try? readStrings(service: Self.notesKeychainService)) ?? [:]
    }

    @discardableResult
    public func saveNote(_ note: String, forInviteID id: String) -> Bool {
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return true }
        return mutate(service: Self.notesKeychainService) { $0[id] = trimmed }
    }

    @discardableResult
    public func removeNote(forInviteID id: String) -> Bool {
        mutate(service: Self.notesKeychainService) { $0[id] = nil }
    }

    /// Claims one note before enqueueing. Only a successful own-group write permits sending it.
    public func takeNote(forInviteID id: String) -> String? {
        var note: String?
        let written = mutate(service: Self.notesKeychainService) { note = $0.removeValue(forKey: id) }
        return written ? note : nil
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
    
    private func query(service: String, group: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: userID,
         kSecAttrAccessGroup as String: group,
         kSecAttrSynchronizable as String: false]
    }

    private func readStrings(service: String) throws -> [String: String] {
        for group in [ownedAccessGroup].compactMap({ $0 }) + legacyReadGroups {
            var request = query(service: service, group: group)
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            let (status, result) = keychain.copyMatching(request)
            if status == errSecItemNotFound { continue }
            guard status == errSecSuccess, let data = result as? Data else { throw StorageError.unavailable }
            return try JSONDecoder().decode([String: String].self, from: data)
        }
        return [:]
    }

    private func mutate(service: String, transform: (inout [String: String]) -> Void) -> Bool {
        guard let ownedAccessGroup else { return false }
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        guard var current = try? readStrings(service: service) else { return false }
        transform(&current)
        guard let data = try? JSONEncoder().encode(current) else { return false }
        let request = query(service: service, group: ownedAccessGroup)
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = keychain.update(request, attributes: attributes)
        if status == errSecItemNotFound {
            return keychain.add(request.merging(attributes) { $1 }) == errSecSuccess
        }
        return status == errSecSuccess
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
