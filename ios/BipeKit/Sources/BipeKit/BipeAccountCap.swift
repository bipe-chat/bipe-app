// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Personal accounts on one phone (ADR 0086). Company accounts do not count (bipe#269). The Android counterpart is
/// `BipeAccountCap` in libraries/bipe.
public nonisolated enum BipeAccountCap {
    private static let maxKey = "bipe.accounts.maxPersonal"
    
    /// The last `max_accounts` any account read from `GET /v1/me/account`, else the shared registry value.
    public static func maxPersonalAccounts(defaults: UserDefaults = .standard) -> Int {
        let stored = defaults.integer(forKey: maxKey)
        return stored > 0 ? stored : BipeRegistries.maxPersonalAccounts
    }
    
    /// `maxAccounts` is `nil` from an older server: the stored value stays.
    public static func onServerValue(_ maxAccounts: Int?, defaults: UserDefaults = .standard) {
        guard let maxAccounts, maxAccounts > 0 else { return }
        defaults.set(maxAccounts, forKey: maxKey)
    }
    
    public static func canAdd(personalAccounts: Int, defaults: UserDefaults = .standard) -> Bool {
        personalAccounts < maxPersonalAccounts(defaults: defaults)
    }
    
    /// Counts the personal accounts among `signedInUserIDs`; company accounts do not count.
    public static func canAdd(signedInUserIDs: some Collection<String>, defaults: UserDefaults = .standard) -> Bool {
        let personal = signedInUserIDs.filter { !BipeAccountLabels.isCompany($0, defaults: defaults) }.count
        return canAdd(personalAccounts: personal, defaults: defaults)
    }
}

/// The name each account on this phone showed last, so the account menu can name accounts without a live session.
public nonisolated enum BipeAccountLabels {
    private static func key(_ userID: String) -> String {
        "bipe.accounts.label.\(userID)"
    }
    
    public static func remember(displayName: String?, for userID: String, defaults: UserDefaults = .standard) {
        guard let displayName, !displayName.isEmpty else { return }
        defaults.set(displayName, forKey: key(userID))
    }
    
    /// The remembered name, else the Matrix ID.
    public static func label(for userID: String, defaults: UserDefaults = .standard) -> String {
        defaults.string(forKey: key(userID)) ?? userID
    }
    
    /// ADR 0087: a company account does not count in the cap, and the app shows it as the company.
    public static func rememberCompany(_ isCompany: Bool, for userID: String, defaults: UserDefaults = .standard) {
        defaults.set(isCompany, forKey: companyKey(userID))
    }
    
    public static func isCompany(_ userID: String, defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: companyKey(userID))
    }
    
    public static func forget(_ userID: String, defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: key(userID))
        defaults.removeObject(forKey: companyKey(userID))
    }
    
    private static func companyKey(_ userID: String) -> String {
        "bipe.accounts.company.\(userID)"
    }
}
