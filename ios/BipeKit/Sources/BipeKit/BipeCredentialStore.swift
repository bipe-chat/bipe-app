// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from BipeKit (bipe-ios Components/BipeKit/Sources/BipeKit/BipeCredentialStore.swift).

import Foundation
import Security

/// Keychain storage for a device-first account (ADR 0057, ADR 0059). Nothing here is ever shown to the user or logged.
///
/// - The hidden MAS password stays on this device: `AfterFirstUnlockThisDeviceOnly`, never synced, not in backups.
/// - The recovery token syncs through iCloud Keychain (`kSecAttrSynchronizable`). On a new phone the token and the PIN
///   get a new password from the API (`POST /v1/recovery/token`). The token alone gives no access.
/// - A pending email recovery (its claim secret) stays on this device.
/// - Every account has its own password and token item, keyed by Matrix ID in `kSecAttrAccount` (ADR 0086). A second
///   account never replaces the first. Items from before bipe#265 already carry the Matrix ID there, so nothing moves.
public nonisolated struct BipeCredentialStore: Sendable {
    /// A credential and the account it belongs to.
    public struct Item: Sendable, Equatable, CustomStringConvertible {
        public let userID: String
        public let secret: String
        
        /// Never prints the secret.
        public var description: String {
            "BipeCredentialStore.Item(\(userID))"
        }
    }
    
    private let ownedAccessGroup: String?
    private let legacyReadGroups: [String]
    private let keychain: any CredentialKeychain
    
    private enum Service: String {
        case password = "chat.bipe.account.password"
        case recoveryToken = "chat.bipe.account.recovery-token"
        case pendingRecovery = "chat.bipe.account.pending-recovery"
        case pendingSignup = "chat.bipe.account.pending-signup"
        case passwordExcluded = "chat.bipe.account.password.excluded"
        case tokenExcluded = "chat.bipe.account.recovery-token.excluded"
    }
    
    /// Every service this store writes, sign-out markers first: the app-group move (bipe-chat/bipe#531) copies a marker
    /// before the items it hides.
    public static let keychainServices: [String] = [Service.passwordExcluded, .tokenExcluded, .password, .recoveryToken,
                                                    .pendingSignup, .pendingRecovery].map(\.rawValue)

    /// A sign-up whose credentials are not stored yet (ADR 0094). The key makes a retry return the same account.
    /// The PIN is the one the first try sent, so the app lock gets the PIN the account really has.
    public struct PendingSignup: Codable, Sendable, Equatable, CustomStringConvertible {
        public let key: String
        public var pin: String?
        
        /// Never prints the key or the PIN.
        public var description: String {
            "PendingSignup"
        }
    }
    
    /// Mutations require the app's explicit own group (ADR 0147): the app-group keychain group since bipe-chat/bipe#531,
    /// so the items survive the App Store transfer. The Team-ID group before it is a legacy read group now.
    /// A missing or unresolved group disables mutations; it must never become an unscoped query.
    public init(accessGroup: String? = nil) {
        self.init(ownedAccessGroup: accessGroup ?? (Bundle.main.object(forInfoDictionaryKey: "BipeOwnedKeychainGroup") as? String),
                  legacyReadGroups: Bundle.main.object(forInfoDictionaryKey: "BipeLegacyKeychainReadGroups") as? [String] ?? [],
                  keychain: SystemCredentialKeychain())
    }

    init(ownedAccessGroup: String?, legacyReadGroups: [String], keychain: any CredentialKeychain) {
        self.ownedAccessGroup = ownedAccessGroup.flatMap { !$0.isEmpty && !$0.contains("$(") ? $0 : nil }
        self.legacyReadGroups = legacyReadGroups.filter { !$0.isEmpty && !$0.contains("$(") && $0 != ownedAccessGroup }
        self.keychain = keychain
    }
    
    // MARK: - Credentials
    
    /// Stores the password on this device and syncs the token. Replaces what was stored before for this account only.
    /// `false` when the password cannot be read back: keep any pending sign-up, it is the only other copy.
    @discardableResult
    public func save(_ credentials: BipeCredentials) -> Bool {
        let saved = write(.password, account: credentials.userId, value: Data(credentials.password.utf8), synchronizable: false)
        saveRecoveryToken(credentials.recoveryToken, for: credentials.userId)
        return saved && password(for: credentials.userId) == Item(userID: credentials.userId, secret: credentials.password)
    }
    
    public func saveRecoveryToken(_ token: String, for userID: String) {
        write(.recoveryToken, account: userID, value: Data(token.utf8), synchronizable: true)
    }
    
    /// The newest password stored on this device, if any.
    public func password() -> Item? {
        read(.password).flatMap(Self.item)
    }
    
    public func password(for userID: String) -> Item? {
        read(.password, account: userID).flatMap(Self.item)
    }
    
    /// The newest recovery token, from this device or synced from another device of the same Apple account.
    public func recoveryToken() -> Item? {
        read(.recoveryToken).flatMap(Self.item)
    }
    
    /// The newest recovery token of an account not in `signedIn`: adding an account never recovers one already on the phone.
    public func recoveryToken(excluding signedIn: Set<String>) -> Item? {
        readAll(.recoveryToken).filter { !signedIn.contains($0.account) }.first.flatMap(Self.item)
    }
    
    public func recoveryToken(for userID: String) -> Item? {
        read(.recoveryToken, account: userID).flatMap(Self.item)
    }
    
    /// After an explicit sign-out: the next sign-in of this account on this device needs the token and the PIN.
    public func deletePassword(for userID: String) {
        guard excludeLegacy(.password, account: userID) else { return }
        delete(.password, account: userID)
    }
    
    /// When the account that signs out is not known: no account on this device signs in silently again.
    public func deleteAllPasswords() {
        let accounts = records(.password).map(\.account)
        guard accounts.allSatisfy({ excludeLegacy(.password, account: $0) }) else { return }
        delete(.password)
    }
    
    /// After the API rejected the token (`recovery_invalid`).
    public func deleteRecoveryToken(for userID: String) {
        guard excludeLegacy(.recoveryToken, account: userID) else { return }
        delete(.recoveryToken, account: userID)
    }
    
    // MARK: - Pending sign-up
    
    /// The pending sign-up, or a new one with a fresh key. A new one is stored before the first request goes out.
    public func pendingSignup(pin: String?) -> PendingSignup {
        if let pending = pendingSignup() {
            return pending
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            fatalError("SecRandomCopyBytes failed")
        }
        let key = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let pending = PendingSignup(key: key, pin: pin)
        savePendingSignup(pending)
        return pending
    }
    
    public func pendingSignup() -> PendingSignup? {
        read(.pendingSignup).flatMap { try? Self.decoder.decode(PendingSignup.self, from: $0.data) }
    }
    
    /// After the server said it made nothing (a 4xx answer): the next try may send a different PIN.
    public func updatePendingSignupPIN(_ pin: String?) {
        guard var pending = pendingSignup() else { return }
        pending.pin = pin
        savePendingSignup(pending)
    }
    
    public func deletePendingSignup() {
        delete(.pendingSignup)
    }
    
    private func savePendingSignup(_ pending: PendingSignup) {
        guard let data = try? Self.encoder.encode(pending) else { return }
        write(.pendingSignup, account: "pending", value: data, synchronizable: false)
    }
    
    // MARK: - Pending recovery
    
    public func savePendingRecovery(_ recovery: BipeRecoveryStarted) {
        guard let data = try? Self.encoder.encode(recovery) else { return }
        write(.pendingRecovery, account: "pending", value: data, synchronizable: false)
    }
    
    public func pendingRecovery() -> BipeRecoveryStarted? {
        read(.pendingRecovery).flatMap { try? Self.decoder.decode(BipeRecoveryStarted.self, from: $0.data) }
    }
    
    public func deletePendingRecovery() {
        delete(.pendingRecovery)
    }
    
    // MARK: - Private
    
    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()
    
    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
    
    private static func item(_ stored: (account: String, data: Data)) -> Item? {
        String(data: stored.data, encoding: .utf8).map { Item(userID: stored.account, secret: $0) }
    }
    
    private func baseQuery(_ service: Service, group: String, account: String? = nil) -> [String: Any] {
        var query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service.rawValue,
                                    kSecAttrAccessGroup as String: group,
                                    kSecAttrSynchronizable as String: kSecAttrSynchronizableAny]
        if let account { query[kSecAttrAccount as String] = account }
        return query
    }

    /// Update in place: a failed add/update never deletes the previous value or any legacy item.
    @discardableResult
    private func write(_ service: Service, account: String, value: Data, synchronizable: Bool) -> Bool {
        guard let ownedAccessGroup else { return false }
        let query = baseQuery(service, group: ownedAccessGroup, account: account)
        let attributes: [String: Any] = [
            kSecValueData as String: value,
            kSecAttrSynchronizable as String: synchronizable,
            kSecAttrAccessible as String: synchronizable ? kSecAttrAccessibleAfterFirstUnlock : kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = keychain.update(query, attributes: attributes)
        if status == errSecItemNotFound {
            var item = query
            item.merge(attributes) { _, new in new }
            status = keychain.add(item)
            // Another writer may have added the same own-group item between the update and add.
            if status == errSecDuplicateItem { status = keychain.update(query, attributes: attributes) }
        }
        if status != errSecSuccess {
            NSLog("Bipe keychain write failed for \(service.rawValue): \(status)")
        }
        if status == errSecSuccess, let exclusion = exclusionService(service), !records(exclusion, account: account).isEmpty {
            delete(exclusion, account: account)
        }
        return status == errSecSuccess
    }

    private func exclusionService(_ service: Service) -> Service? {
        switch service {
        case .password: .passwordExcluded
        case .recoveryToken: .tokenExcluded
        default: nil
        }
    }

    /// A local own-group marker prevents legacy fallback from silently undoing sign-out or token rejection.
    private func excludeLegacy(_ service: Service, account: String) -> Bool {
        guard let exclusion = exclusionService(service) else { return false }
        return write(exclusion, account: account, value: Data(), synchronizable: false)
    }

    private struct Stored {
        let account: String
        let data: Data
        let created: Date
        let modified: Date
    }

    /// Read own items first, then the configured legacy groups. Dedupe by account before sorting by date.
    /// Pending operations belong to this app alone; only passwords and recovery tokens have legacy fallback.
    /// A sign-out marker in the own group hides every copy; a marker in a legacy group (the Team-ID group before the
    /// app-group move, bipe-chat/bipe#531, or the fork's) hides only legacy copies, so a later own save shows again.
    private func records(_ service: Service, account: String? = nil) -> [Stored] {
        var groups = ownedAccessGroup.map { [$0] } ?? []
        if service == .password || service == .recoveryToken { groups += legacyReadGroups }
        let exclusion = exclusionService(service)
        let ownExcluded = exclusion.map { Set(markerAccounts($0, in: ownedAccessGroup)) } ?? []
        let legacyExcluded = exclusion.map { marker in ownExcluded.union(legacyReadGroups.flatMap { markerAccounts(marker, in: $0) }) } ?? []
        var selected: [String: Stored] = [:]
        for group in groups {
            let excluded = group == ownedAccessGroup ? ownExcluded : legacyExcluded
            var query = baseQuery(service, group: group, account: account)
            query[kSecReturnData as String] = true
            query[kSecReturnAttributes as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitAll
            let (status, result) = keychain.copyMatching(query)
            if status != errSecSuccess, status != errSecItemNotFound, group == ownedAccessGroup { return [] }
            guard status == errSecSuccess, let items = result as? [[String: Any]] else { continue }
            for item in items {
                guard let id = item[kSecAttrAccount as String] as? String,
                      let data = item[kSecValueData as String] as? Data, selected[id] == nil, !excluded.contains(id) else { continue }
                selected[id] = Stored(account: id, data: data,
                                      created: item[kSecAttrCreationDate as String] as? Date ?? .distantFuture,
                                      modified: item[kSecAttrModificationDate as String] as? Date ?? .distantPast)
            }
        }
        return selected.values.sorted { $0.modified > $1.modified }
    }

    /// Discovery keeps the creation date for the oldest-account default, while preferring the own-group value.
    public func passwordsWithCreationDates() -> [(item: Item, created: Date)] {
        records(.password).compactMap { record in
            Self.item((record.account, record.data)).map { ($0, record.created) }
        }
    }

    /// The accounts of the sign-out markers in one group. An unreadable group counts as none.
    private func markerAccounts(_ service: Service, in group: String?) -> [String] {
        guard let group else { return [] }
        var query = baseQuery(service, group: group)
        query[kSecReturnAttributes as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        let (status, result) = keychain.copyMatching(query)
        guard status == errSecSuccess, let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }
    }

    private func readAll(_ service: Service) -> [(account: String, data: Data)] {
        records(service).map { ($0.account, $0.data) }
    }

    private func read(_ service: Service, account: String? = nil) -> (account: String, data: Data)? {
        records(service, account: account).first.map { ($0.account, $0.data) }
    }

    /// Every mutation query, including all-account deletes, names only the own group.
    private func delete(_ service: Service, account: String? = nil) {
        guard let ownedAccessGroup else { return }
        _ = keychain.delete(baseQuery(service, group: ownedAccessGroup, account: account))
    }
}
