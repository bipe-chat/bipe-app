// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

import Foundation

/// Links in an official notice (ADR 0036, bipe-chat/bipe#245). Must match bipe-shared tools/lib/notice.mjs and
/// test-vectors/notice-links.json. The rule is lexical, not a URL parser, so every platform agrees. Fail closed.
public nonisolated enum BipeNoticeLinks {
    /// A verified domain in an `institution` badge: lower case, at least one dot.
    static func isDomain(_ value: String) -> Bool {
        value.wholeMatch(of: /[a-z0-9-]+(\.[a-z0-9-]+)+/) != nil
    }
    
    /// True when `url` may be a clickable link in a notice from an institution with the verified `domains`.
    public static func isClickable(_ url: String, domains: [String]) -> Bool {
        guard url.prefix(8).lowercased() == "https://" else { return false }
        let rest = url.dropFirst(8)
        let authority = rest.prefix { $0 != "/" && $0 != "?" && $0 != "#" }
        // No user info (@), no port (:), no IP literal ([), no other characters.
        guard !authority.isEmpty, authority.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "." || $0 == "-") }) else {
            return false
        }
        var host = authority.lowercased()
        if host.hasSuffix(".") {
            host.removeLast()
        }
        guard isDomain(host) else { return false }
        return domains.contains { domain in
            isDomain(domain) && (host == domain || host.hasSuffix(".\(domain)"))
        }
    }
}
