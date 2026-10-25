// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation
import Security

/// Copies Bipe's keychain items into the app-group keychain group before the App Store transfer (bipe-chat/bipe#531,
/// bipe/docs/team-transfer-recovery.md section 3). The Team-ID groups become unreadable after the first update from the
/// new team; the app group moves with the app, so its keychain group stays readable.
///
/// Fail closed:
/// - It never deletes or changes an item in a source group.
/// - It copies an item only when the target has no item of the same service and account, and keeps the item's
///   accessibility class and sync attribute.
/// - Services run in the order given: sign-out markers first, so a signed-out account never comes back.
/// - `done` is true only when every source item has a target item and every copy reads back with the same value.
/// - It logs only the service and the `OSStatus`, never a value.
public nonisolated struct BipeKeychainMove: Sendable {
    public struct Result: Sendable, Equatable {
        /// Every source item has a copy in the target group.
        public let done: Bool
        /// Items copied in this run.
        public let copied: Int
    }

    /// The marker that a finished move leaves in the target group. With it, `run()` does nothing.
    static let markerService = "chat.bipe.keychain-move"

    private let target: String?
    private let sources: [String]
    private let services: [String]
    private let keychain: any CredentialKeychain

    /// The app's groups from Info.plist (project.yml): the target is BipeOwnedKeychainGroup (the app group), the
    /// sources are BipeLegacyKeychainReadGroups (the Team-ID group and the fork's group). `services` in move order.
    public init(services: [String]) {
        self.init(target: Bundle.main.object(forInfoDictionaryKey: "BipeOwnedKeychainGroup") as? String,
                  sources: Bundle.main.object(forInfoDictionaryKey: "BipeLegacyKeychainReadGroups") as? [String] ?? [],
                  services: services,
                  keychain: SystemCredentialKeychain())
    }

    init(target: String?, sources: [String], services: [String], keychain: any CredentialKeychain) {
        let expanded = { (group: String) in !group.isEmpty && !group.contains("$(") }
        self.target = target.flatMap { expanded($0) ? $0 : nil }
        self.sources = sources.filter { expanded($0) && $0 != target }
        self.services = services
        self.keychain = keychain
    }

    /// True when a finished move left its marker in the target group.
    public var isDone: Bool {
        guard let target else { return false }
        return !items(Self.markerService, in: target).isEmpty
    }

    /// Runs the move once per launch, before any keychain read. Cheap when the marker is there.
    @discardableResult
    public func run() -> Result {
        guard let target else { return Result(done: false, copied: 0) }
        if isDone { return Result(done: true, copied: 0) }
        var copied = 0
        var complete = true
        for service in services {
            let present = Set(items(service, in: target).compactMap { $0[kSecAttrAccount as String] as? String })
            var seen = present
            for source in sources {
                for item in items(service, in: source) {
                    guard let account = item[kSecAttrAccount as String] as? String,
                          let data = item[kSecValueData as String] as? Data else { continue }
                    // The first source in order wins, as in BipeCredentialStore: the Team-ID group before the fork.
                    guard !seen.contains(account) else { continue }
                    seen.insert(account)
                    if copy(item, service: service, account: account, data: data, to: target) {
                        copied += 1
                    } else {
                        complete = false
                    }
                }
            }
        }
        guard complete else { return Result(done: false, copied: copied) }
        let marker = keychain.add([kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: Self.markerService,
                                   kSecAttrAccount as String: "done",
                                   kSecAttrAccessGroup as String: target,
                                   kSecAttrSynchronizable as String: false,
                                   kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                   kSecValueData as String: Data()])
        return Result(done: marker == errSecSuccess || marker == errSecDuplicateItem, copied: copied)
    }

    // MARK: - Private

    private func items(_ service: String, in group: String) -> [[String: Any]] {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecAttrAccessGroup as String: group,
                                    kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
                                    kSecReturnData as String: true,
                                    kSecReturnAttributes as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitAll]
        let (status, result) = keychain.copyMatching(query)
        if status != errSecSuccess, status != errSecItemNotFound {
            NSLog("Bipe keychain move read failed for \(service): \(status)")
        }
        return status == errSecSuccess ? result as? [[String: Any]] ?? [] : []
    }

    /// Adds one copy and reads it back. A copy that another writer added in between counts when it reads back.
    private func copy(_ item: [String: Any], service: String, account: String, data: Data, to target: String) -> Bool {
        let synchronizable = (item[kSecAttrSynchronizable as String] as? Bool) ?? false
        let accessible = item[kSecAttrAccessible as String] as? String
            ?? (synchronizable ? kSecAttrAccessibleAfterFirstUnlock : kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) as String
        let status = keychain.add([kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccount as String: account,
                                   kSecAttrAccessGroup as String: target,
                                   kSecAttrSynchronizable as String: synchronizable,
                                   kSecAttrAccessible as String: accessible,
                                   kSecValueData as String: data])
        if status != errSecSuccess, status != errSecDuplicateItem {
            NSLog("Bipe keychain move write failed for \(service): \(status)")
            return false
        }
        let back = items(service, in: target).first { $0[kSecAttrAccount as String] as? String == account }
        guard let copy = back?[kSecValueData as String] as? Data else { return false }
        // A duplicate written by another writer between the read and the add is newer; it stays as it is.
        return status == errSecDuplicateItem || copy == data
    }
}
