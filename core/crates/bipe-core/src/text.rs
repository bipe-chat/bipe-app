// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! `normalize()` of bipe-shared tools/lib/registries.mjs (docs/scam-matching.md). Vectors: test-vectors/normalization.json.

use crate::generated::registries::{HOMOGLYPHS, HOST_HOMOGLYPHS};
use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

/// The homoglyph map that normalize() folds with.
#[derive(uniffi::Enum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum TextFold {
    /// registries/homoglyphs.json: display names, reserved names, contacts, handles.
    Names,
    /// The host map: link hosts in scam matching.
    Hosts,
    /// No folding: scam phrases and keywords.
    Plain,
}

/// NFKD, marks removed, lowercase, homoglyphs folded, each JavaScript whitespace run one space, trimmed.
#[uniffi::export]
pub fn normalize_text(input: String, fold: TextFold) -> String {
    normalize(&input, fold)
}

pub(crate) fn normalize(input: &str, fold: TextFold) -> String {
    let stripped: String = input.nfkd().filter(|c| !is_combining_mark(*c)).collect();
    // Full Unicode lowercase with the final sigma rule, as JavaScript toLowerCase().
    let lowered = stripped.to_lowercase();
    let map: &[(char, &str)] = match fold {
        TextFold::Names => HOMOGLYPHS,
        TextFold::Hosts => HOST_HOMOGLYPHS,
        TextFold::Plain => &[],
    };
    let mut folded = String::with_capacity(lowered.len());
    for c in lowered.chars() {
        match map.binary_search_by(|(key, _)| key.cmp(&c)) {
            Ok(index) => folded.push_str(map[index].1),
            Err(_) => folded.push(c),
        }
    }
    // The reference folds first, then collapses whitespace, then trims.
    let mut out = String::with_capacity(folded.len());
    let mut pending_space = false;
    for c in folded.chars() {
        if is_js_space(c) {
            pending_space = true;
            continue;
        }
        if pending_space && !out.is_empty() {
            out.push(' ');
        }
        pending_space = false;
        out.push(c);
    }
    out
}

/// JavaScript `\s` and the `trim()` set: WhiteSpace with every Zs character, and LineTerminator.
/// Not Rust `char::is_whitespace`: U+0085 is not in it, and U+FEFF is.
pub(crate) fn is_js_space(c: char) -> bool {
    matches!(
        c,
        '\t' | '\n'
            | '\u{0B}'
            | '\u{0C}'
            | '\r'
            | ' '
            | '\u{A0}'
            | '\u{1680}'
            | '\u{2000}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{202F}'
            | '\u{205F}'
            | '\u{3000}'
            | '\u{FEFF}'
    )
}

/// JavaScript `String.prototype.trim()`.
pub(crate) fn js_trim(text: &str) -> &str {
    text.trim_matches(is_js_space)
}

/// The UTF-16 length of a string, as JavaScript `length`.
pub(crate) fn utf16_len(text: &str) -> usize {
    text.chars().map(char::len_utf16).sum()
}

/// The byte offset of a UTF-16 offset, or `None` inside a surrogate pair or past the end.
pub(crate) fn byte_offset(text: &str, utf16: usize) -> Option<usize> {
    let mut units = 0;
    for (byte, c) in text.char_indices() {
        if units == utf16 {
            return Some(byte);
        }
        if units > utf16 {
            return None;
        }
        units += c.len_utf16();
    }
    (units == utf16).then_some(text.len())
}

/// Every match of a translated registry regex, as JavaScript `matchAll` finds them: the byte ranges of capture group 1
/// (bipe-shared docs/scam-matching.md "Iteration"). With `guard_before`, the pattern consumes the character before the
/// match, so each next search starts one character early to let the guard read the end of the previous match.
pub(crate) fn find_all(regex: &regex::Regex, guard_before: bool, text: &str) -> Vec<std::ops::Range<usize>> {
    let mut found = Vec::new();
    let mut start = 0;
    while start <= text.len() {
        let from = if guard_before && start > 0 { text[..start].char_indices().next_back().map_or(0, |(i, _)| i) } else { start };
        let Some(m) = regex.captures_at(text, from).and_then(|caps| caps.get(1)) else { break };
        found.push(m.range());
        start = if m.end() > m.start() {
            m.end()
        } else {
            match text[m.end()..].chars().next() {
                Some(ch) => m.end() + ch.len_utf8(),
                None => break,
            }
        };
    }
    found
}
