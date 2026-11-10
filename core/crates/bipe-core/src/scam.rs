// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Scam matching (security.md rule 9). Reference: bipe-shared tools/lib/registries.mjs (`scamMatchIDs`, `linkHosts`,
//! `scamWarningKind`). Port rules: bipe-shared docs/scam-matching.md. Vectors: test-vectors/scam-detection.json and
//! scam-warnings.json. Friction, not protection.

use crate::generated::registries::*;
use crate::text::{TextFold, byte_offset, normalize, utf16_len};
use regex::Regex;
use std::sync::OnceLock;

/// The scam pattern a message looks like. The app shows one warning per kind.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ScamKind {
    Extortion,
    PixCode,
    Boleto,
    RemoteApp,
    PinRequest,
    PixKey,
    DeliveryFee,
    LookalikeLink,
    BankAlert,
    Receipt,
    NewNumber,
    Urgency,
}

impl ScamKind {
    fn from_id(id: &str) -> Option<Self> {
        Some(match id {
            "extortion" => Self::Extortion,
            "pixCode" => Self::PixCode,
            "boleto" => Self::Boleto,
            "remoteApp" => Self::RemoteApp,
            "pinRequest" => Self::PinRequest,
            "pixKey" => Self::PixKey,
            "deliveryFee" => Self::DeliveryFee,
            "lookalikeLink" => Self::LookalikeLink,
            "bankAlert" => Self::BankAlert,
            "receipt" => Self::Receipt,
            "newNumber" => Self::NewNumber,
            "urgency" => Self::Urgency,
            _ => return None,
        })
    }
}

/// Match IDs in reference order: `<patternGroup>:<id>` for the regexes on the raw message, then
/// `urgency:<phrase>`, then `receipt:<keyword>` on the message normalised without folding.
#[uniffi::export]
pub fn scam_match_ids(message: String) -> Vec<String> {
    match_ids(&message)
}

/// The one warning to show for a message, or `None`: the first kind in warningKindPriority whose rule applies.
/// Kinds in `skipping` are passed over.
#[uniffi::export]
pub fn scam_warning_kind(message: String, skipping: Vec<ScamKind>) -> Option<ScamKind> {
    let ids = match_ids(&message);
    let has_group = |group: &str| ids.iter().any(|id| id.split(':').next() == Some(group));
    let phrases: Vec<&str> = ids.iter().filter_map(|id| id.strip_prefix("urgency:")).collect();
    let n = normalize(&message, TextFold::Plain);
    let hosts = link_hosts(&message);
    let any = |words: &[&str]| words.iter().any(|w| n.contains(w));

    SCAM_WARNING_KIND_PRIORITY.iter().filter_map(|id| ScamKind::from_id(id)).find(|kind| {
        !skipping.contains(kind)
            && match kind {
                // A threat with a payment demand. A threat alone is not this kind.
                ScamKind::Extortion => any(SCAM_EXTORTION_THREAT_PHRASES) && any(SCAM_EXTORTION_DEMAND_WORDS),
                ScamKind::PixCode => has_group("pixCopyPastePatterns"),
                ScamKind::Boleto => has_group("boletoPatterns"),
                // A remote-access app by name, or a link that is not an official or app-store address in a message
                // about a bank, a card or Pix.
                ScamKind::RemoteApp => {
                    contains_word(&n, SCAM_REMOTE_APP_INSTALL_WORDS)
                        && (contains_word(&n, SCAM_REMOTE_APP_NAMES)
                            || (hosts.iter().any(|h| {
                                !is_under(&h.plain, SCAM_OFFICIAL_DOMAINS) && !is_under(&h.plain, SCAM_APP_STORE_DOMAINS)
                            }) && any(SCAM_REMOTE_APP_BANK_WORDS)))
                }
                // Fake support asks for the PIN or a code. Bipe never asks for either.
                ScamKind::PinRequest => {
                    any(SCAM_PIN_REQUEST_ASK_PHRASES)
                        && contains_word(&n, SCAM_PIN_REQUEST_SECRET_WORDS)
                        && contains_word(&n, SCAM_PIN_REQUEST_SUPPORT_WORDS)
                }
                // A phone number or e-mail alone is normal chat: only with a word about Pix.
                ScamKind::PixKey => has_group("pixKeyPatterns") && any(SCAM_PIX_KEY_CONTEXT_WORDS),
                ScamKind::DeliveryFee => {
                    !hosts.is_empty()
                        && any(SCAM_DELIVERY_PARCEL_WORDS)
                        && any(SCAM_DELIVERY_HOLD_WORDS)
                        && any(SCAM_DELIVERY_FEE_WORDS)
                }
                ScamKind::LookalikeLink => hosts.iter().any(is_lookalike),
                ScamKind::BankAlert => {
                    !hosts.is_empty() && any(SCAM_BANK_ALERT_SUBJECT_WORDS) && any(SCAM_BANK_ALERT_THREAT_WORDS)
                }
                ScamKind::Receipt => has_group("receipt"),
                // The falso parente opener. "New account" alone is normal chat on Bipe.
                ScamKind::NewNumber => {
                    phrases.iter().any(|p| SCAM_NEW_NUMBER_PHRASES.contains(p))
                        || (any(SCAM_NEW_ACCOUNT_PHRASES) && (!phrases.is_empty() || contains_word(&n, FAMILY_TERMS)))
                }
                ScamKind::Urgency => !phrases.is_empty(),
            }
    })
}

/// A link host. `folded` is a linkPatterns match on the host-folded message. `plain` is the same UTF-16 range of
/// the message normalised without folding, or "" when the two texts differ in length.
pub(crate) struct LinkHost {
    pub(crate) folded: String,
    pub(crate) plain: String,
}

struct Compiled {
    pattern: &'static ScamPattern,
    regex: Regex,
}

/// bipe-shared generates only patterns in the supported subset; a test checks that every one compiles.
fn compiled() -> &'static [Compiled] {
    static COMPILED: OnceLock<Vec<Compiled>> = OnceLock::new();
    COMPILED.get_or_init(|| {
        SCAM_PATTERNS
            .iter()
            .filter_map(|pattern| Regex::new(pattern.regex).ok().map(|regex| Compiled { pattern, regex }))
            .collect()
    })
}

/// The number of generated patterns that compile. Tests compare it with the registry.
#[doc(hidden)]
pub fn scam_patterns_compiled() -> (usize, usize) {
    (compiled().len(), SCAM_PATTERNS.len())
}

fn match_ids(message: &str) -> Vec<String> {
    let mut ids: Vec<String> = compiled()
        .iter()
        .filter(|c| c.regex.is_match(message))
        .map(|c| format!("{}:{}", c.pattern.group, c.pattern.id))
        .collect();
    let n = normalize(message, TextFold::Plain);
    ids.extend(SCAM_URGENCY_PHRASES.iter().filter(|p| n.contains(*p)).map(|p| format!("urgency:{p}")));
    ids.extend(SCAM_RECEIPT_KEYWORDS.iter().filter(|k| n.contains(*k)).map(|k| format!("receipt:{k}")));
    ids
}

/// Every link host in the message, as JavaScript `matchAll` finds them.
pub(crate) fn link_hosts(message: &str) -> Vec<LinkHost> {
    let plain = normalize(message, TextFold::Plain);
    let folded = normalize(message, TextFold::Hosts);
    let aligned = utf16_len(&plain) == utf16_len(&folded);
    let mut hosts = Vec::new();
    for c in compiled().iter().filter(|c| c.pattern.group == "linkPatterns") {
        let mut start = 0;
        while start <= folded.len() {
            // A leading guard consumes one character: start one character earlier so it can read the last
            // character of the previous match, as a JavaScript look-behind does.
            let from = if c.pattern.guard_before && start > 0 {
                folded[..start].char_indices().next_back().map_or(0, |(i, _)| i)
            } else {
                start
            };
            let Some(m) = c.regex.captures_at(&folded, from).and_then(|caps| caps.get(1)) else { break };
            let plain_host = if aligned {
                let from16 = utf16_len(&folded[..m.start()]);
                let to16 = from16 + utf16_len(m.as_str());
                match (byte_offset(&plain, from16), byte_offset(&plain, to16)) {
                    (Some(a), Some(b)) => plain[a..b].to_owned(),
                    _ => String::new(),
                }
            } else {
                String::new()
            };
            hosts.push(LinkHost { folded: m.as_str().to_owned(), plain: plain_host });
            start = if m.end() > m.start() {
                m.end()
            } else {
                match folded[m.end()..].chars().next() {
                    Some(ch) => m.end() + ch.len_utf8(),
                    None => break,
                }
            };
        }
    }
    hosts
}

/// True when the host copies a brand: its folded form names a brand and its plain form is not that brand's domain.
/// A short host label must be a whole label, so "receitas.globo.com" does not match "receita".
fn is_lookalike(host: &LinkHost) -> bool {
    let squashed: String = host.folded.chars().filter(|c| *c != '.' && *c != '-').collect();
    let names_brand = SCAM_LOOKALIKE_BRAND_TOKENS.iter().any(|t| squashed.contains(t))
        || host.folded.split(['.', '-']).any(|label| SCAM_LOOKALIKE_HOST_LABELS.contains(&label));
    names_brand && !is_under(&host.plain, SCAM_OFFICIAL_DOMAINS)
}

/// True when the plain host is one of `domains` or a subdomain of one.
fn is_under(plain: &str, domains: &[&str]) -> bool {
    domains.iter().any(|d| plain == *d || plain.strip_suffix(d).is_some_and(|rest| rest.ends_with('.')))
}

/// True when the normalised text has one of `terms` as a whole word: each run of characters outside [0-9a-z]
/// becomes one space, then the term must have a space on each side.
fn contains_word(normalized: &str, terms: &[&str]) -> bool {
    let mut words = String::with_capacity(normalized.len() + 2);
    words.push(' ');
    let mut in_gap = false;
    for c in normalized.chars() {
        if c.is_ascii_digit() || c.is_ascii_lowercase() {
            words.push(c);
            in_gap = false;
        } else if !in_gap {
            words.push(' ');
            in_gap = true;
        }
    }
    words.push(' ');
    terms.iter().any(|t| words.contains(&format!(" {t} ")))
}
