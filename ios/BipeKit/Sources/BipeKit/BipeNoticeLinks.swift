// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import BipeCore
import Foundation

/// Links in an official notice (ADR 0036, bipe-chat/bipe#245). The rule runs in bipe-core (`noticeLinkClickable`,
/// ADR 0267) and matches bipe-shared tools/lib/notice.mjs and test-vectors/notice-links.json. Fail closed.
public nonisolated enum BipeNoticeLinks {
    /// A verified domain in an `institution` badge: lower case, at least one dot.
    static func isDomain(_ value: String) -> Bool {
        noticeDomainValid(domain: value)
    }

    /// True when `url` may be a clickable link in a notice from an institution with the verified `domains`.
    public static func isClickable(_ url: String, domains: [String]) -> Bool {
        noticeLinkClickable(url: url, domains: domains)
    }
}
