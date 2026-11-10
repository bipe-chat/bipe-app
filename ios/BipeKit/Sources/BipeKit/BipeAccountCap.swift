// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// The name each account on this phone showed last, so the account menu can name accounts without a live session.
public nonisolated enum BipeAccountLabels {
    private static func key(_ userID: String) -> String {
        "bipe.accounts.label.\(userID)"
    }
    
    public static func remember(displayName: String?, for userID: String, defaults: UserDefaults = .standard) {
        guard let displayName, !displayName.isEmpty else { return }
        defaults.set(displayName, forKey: key(userID))
    }
    
    /// The remembered name when it is a real name, else the @handle, else Você. Never the Matrix ID (People names).
    public static func label(for userID: String, defaults: UserDefaults = .standard) -> String {
        name(for: userID, defaults: defaults) ?? BipeStrings.tabYou
    }

    /// The remembered name when it is a real name, else the cached @handle, else nil.
    public static func name(for userID: String, defaults: UserDefaults = .standard) -> String? {
        let name = BipeChatRowName.person(userID: userID, displayName: defaults.string(forKey: key(userID)), handle: nil,
                                          placeholder: "")
        return name.isEmpty ? nil : name
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
