// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeKit
import Foundation

/// ADR 0195 point 7: a claim link from the shop on the web waits here until an account is signed in, also through
/// sign-up. The token moves the browser's records to the account, then it stops working.
enum ShopClaim {
    private static let key = "bipe.shopClaim.link"

    /// `true` when `url` is a claim link, which then waits.
    static func keep(_ url: URL) -> Bool {
        guard BipeProfileLink.shopClaim(from: url) != nil else { return false }
        UserDefaults.standard.set(url.absoluteString, forKey: key)
        return true
    }

    static func pending() -> (handle: String, token: String)? {
        UserDefaults.standard.string(forKey: key).flatMap(URL.init(string:)).flatMap(BipeProfileLink.shopClaim(from:))
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}
