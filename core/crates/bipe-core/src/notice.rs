// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Official notice links (bipe ADR 0036, bipe-chat/bipe#245). A link in a notice from an account with a valid
//! institution badge is clickable only on the institution's verified domains. The rule is lexical, not a URL parser.
//! Fail closed: anything else is plain text. Reference: bipe-shared tools/lib/notice.mjs. Vectors:
//! test-vectors/notice-links.json.

/// A verified domain of an `institution` badge: lower-case ASCII labels of letters, digits and hyphens, at least one
/// dot (`NOTICE_DOMAIN` of the reference).
#[uniffi::export]
pub fn notice_domain_valid(domain: String) -> bool {
    is_domain(&domain)
}

/// True when `url` may be a clickable link in a notice from an institution with the verified `domains`.
#[uniffi::export]
pub fn notice_link_clickable(url: String, domains: Vec<String>) -> bool {
    let Some(rest) = url.get(..8).filter(|s| s.eq_ignore_ascii_case("https://")).map(|_| &url[8..]) else {
        return false;
    };
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    // No user info (@), no port (:), no IP literal ([), no other characters.
    if authority.is_empty() || !authority.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-') {
        return false;
    }
    let lower = authority.to_ascii_lowercase();
    let host = lower.strip_suffix('.').unwrap_or(&lower);
    if !is_domain(host) {
        return false;
    }
    domains.iter().any(|d| is_domain(d) && (host == d || host.strip_suffix(d.as_str()).is_some_and(|p| p.ends_with('.'))))
}

fn is_domain(value: &str) -> bool {
    let labels: Vec<&str> = value.split('.').collect();
    labels.len() >= 2
        && labels.iter().all(|l| !l.is_empty() && l.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-'))
}
