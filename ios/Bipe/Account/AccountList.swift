// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// The accounts on this phone in the order they were added, and the one used last (spec account-start notes,
/// ADR 0086). Holds Matrix IDs only, no secrets.
enum AccountList {
    private static let orderKey = "bipe.accounts.order"
    private static let currentKey = "bipe.accounts.current"

    /// Oldest first.
    static var order: [String] {
        get { UserDefaults.standard.stringArray(forKey: orderKey) ?? [] }
        set { UserDefaults.standard.set(newValue, forKey: orderKey) }
    }

    /// The account used last (app-tabs switchAccount).
    static var current: String? {
        get { UserDefaults.standard.string(forKey: currentKey) }
        set { UserDefaults.standard.set(newValue, forKey: currentKey) }
    }

    /// Adds the accounts found on this phone that the list does not know yet, in the order given, and returns the
    /// found accounts in list order. Known accounts keep their place, so the primary account stays first.
    static func merge(found: [String]) -> [String] {
        var list = order
        for userId in found where !list.contains(userId) {
            list.append(userId)
        }
        order = list
        return list.filter(found.contains)
    }

    /// The account to show first: the one used last, else the oldest.
    static func preferred(in accounts: [String]) -> [String] {
        guard let current, accounts.contains(current) else { return accounts }
        return [current] + accounts.filter { $0 != current }
    }

    static func remove(_ userId: String) {
        order.removeAll { $0 == userId }
        if current == userId {
            current = nil
        }
        UserDefaults.standard.removeObject(forKey: ownerKey(userId))
    }

    // MARK: Company owners (bipe-chat/bipe#444)

    /// The personal account that made this company account on this phone. A phone-side grouping only (ADR 0138):
    /// the API never learns it.
    static func owner(of companyId: String) -> String? {
        UserDefaults.standard.string(forKey: ownerKey(companyId))
    }

    static func setOwner(_ ownerId: String, of companyId: String) {
        UserDefaults.standard.set(ownerId, forKey: ownerKey(companyId))
    }

    /// The owner's name, for the company's Sua conta: only while the owner is on this phone and its name is known.
    static func ownerName(of companyId: String) -> String? {
        guard let owner = owner(of: companyId), order.contains(owner) else { return nil }
        let name = BipeAccountLabels.label(for: owner)
        return name == owner ? nil : name
    }

    private static func ownerKey(_ userId: String) -> String {
        "bipe.accounts.owner.\(userId)"
    }
}
