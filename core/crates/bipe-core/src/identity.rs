// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Identity text: handles, invite codes, phone numbers, social handles and the name checks of security rule 8.
//! Reference: bipe-shared tools/lib/registries.mjs. Vectors: handles.json, handle-suggestions.json, invite-codes.json,
//! phone-numbers.json, social-handles.json, profile-checks.json, relationship-names.json.

use crate::generated::registries::*;
use crate::scam::contains_word;
use crate::text::{TextFold, is_js_space, js_trim, normalize, utf16_len};
use regex::Regex;
use std::sync::OnceLock;

/// Why a handle is refused (docs/usernames.md). The ids are shared by every platform.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum HandleProblem {
    TooShort,
    TooLong,
    InvalidCharacters,
    Reserved,
}

/// Why a profile is refused (ADR 0041).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ProfileProblem {
    NameEmpty,
    NameTooLong,
    NameReserved,
    BioTooLong,
    BioTooManyLines,
}

/// Why a social handle is refused (ADR 0228).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SocialHandleProblem {
    Invalid,
    Reserved,
}

/// The warning on a saved contact name that differs from the verified name (ADR 0018).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RelationshipNameWarning {
    /// A full name that is not the verified name.
    Mismatch,
    /// A nickname or a family term: the user confirms it once.
    Confirm,
}

/// Checks a handle without "@". `claimant` absent: a lookup, shape only, and an owned handle may be shorter than
/// the minimum. `claimant` present (the Matrix user ID that wants it, "" for a new account): a handle whose skeleton
/// is an owned handle is reserved for everyone except its owner.
#[uniffi::export]
pub fn handle_problem(handle: String, claimant: Option<String>) -> Option<HandleProblem> {
    let length = utf16_len(&handle);
    if length < HANDLE_OWNER_MIN_LENGTH {
        return Some(HandleProblem::TooShort);
    }
    if length > HANDLE_MAX_LENGTH {
        return Some(HandleProblem::TooLong);
    }
    if length < HANDLE_MIN_LENGTH && owner(&handle).is_none() {
        return Some(HandleProblem::TooShort);
    }
    if !handle_shape(&handle) {
        return Some(HandleProblem::InvalidCharacters);
    }
    let skeleton = skeleton(&handle);
    if let Some(owner) = owner(&skeleton) {
        return match claimant {
            None => None,
            Some(c) if c == owner => None,
            Some(_) => Some(HandleProblem::Reserved),
        };
    }
    reserved_skeleton(&skeleton).then_some(HandleProblem::Reserved)
}

/// The handle without its homoglyphs, separators and spaces. Equal skeletons collide.
#[uniffi::export]
pub fn handle_skeleton(handle: String) -> String {
    skeleton(&handle)
}

/// Escolha seu @ (bipe-chat/bipe#200): handles of the right shape for a display name, in the order the app checks
/// availability. Words are the runs of [a-z0-9] of the name normalised without folding.
#[uniffi::export]
pub fn handle_suggestions(display_name: String) -> Vec<String> {
    let normalized = normalize(&display_name, TextFold::Plain);
    let words: Vec<&str> = normalized.split(|c: char| !(c.is_ascii_lowercase() || c.is_ascii_digit())).filter(|w| !w.is_empty()).collect();
    let (Some(f), Some(l)) = (words.first(), words.last()) else { return Vec::new() };
    let raw = if words.len() > 1 {
        vec![format!("{f}.{l}"), format!("{f}{l}"), format!("{f}_{l}"), f.to_string(), format!("{l}.{f}"), format!("{f}.{l}1"), format!("{f}{l}1")]
    } else {
        vec![f.to_string(), format!("{f}1"), format!("{f}2"), format!("{f}_")]
    };
    let mut out: Vec<String> = Vec::new();
    for candidate in raw {
        // The candidates are ASCII, so a byte cut is a UTF-16 cut.
        let cut = candidate[..candidate.len().min(HANDLE_MAX_LENGTH)].trim_end_matches('.');
        if cut.len() < HANDLE_MIN_LENGTH || !handle_shape(cut) || out.iter().any(|o| o == cut) {
            continue;
        }
        out.push(cut.to_owned());
        if out.len() == HANDLE_SUGGESTION_CANDIDATES {
            break;
        }
    }
    out
}

/// The invite code in anything a user pasted or typed (a code, a link, a whole message), as "XXXX-XXXX-XXXX".
#[uniffi::export]
pub fn extract_invite_code(text: String) -> Option<String> {
    static PATTERN: OnceLock<Option<Regex>> = OnceLock::new();
    let regex = PATTERN.get_or_init(|| Regex::new(INVITE_CODE_PATTERN).ok()).as_ref()?;
    let upper = utf16_prefix(&text, INVITE_CODE_MAX_INPUT_LENGTH).to_uppercase();
    // JavaScript groups 1 to 3 are groups 2 to 4 here: group 1 is the whole match.
    let caps = regex.captures(&upper)?;
    Some(format!("{}-{}-{}", caps.get(2)?.as_str(), caps.get(3)?.as_str(), caps.get(4)?.as_str()))
}

/// A phone number in E.164 from an address-book entry: Brazilian mobiles in any local format, other countries only
/// when written with "+" or "00".
#[uniffi::export]
pub fn normalize_phone(text: String) -> Option<String> {
    let head = utf16_prefix(&text, PHONE_MAX_INPUT_LENGTH).replace("(0)", "");
    let head = js_trim(&head);
    let mut plus = head.chars().find(|c| c.is_ascii_digit() || *c == '+') == Some('+');
    let mut digits: String = head.chars().filter(char::is_ascii_digit).collect();
    if !plus && digits.starts_with("00") {
        plus = true;
        digits.drain(..2);
    }
    let national = PHONE_MOBILE_LENGTH + 2;
    if plus {
        if !digits.starts_with(PHONE_COUNTRY_CODE) {
            // Another country: E.164 as written, length checked only.
            let ok = (PHONE_INTERNATIONAL_MIN_DIGITS..=PHONE_INTERNATIONAL_MAX_DIGITS).contains(&digits.len()) && !digits.starts_with('0');
            return ok.then(|| format!("+{digits}"));
        }
        digits.drain(..PHONE_COUNTRY_CODE.len());
    } else if digits.len() == national + 3 && digits.starts_with('0') {
        digits.drain(..3); // trunk 0 and carrier code
    } else if digits.len() == national + 1 && digits.starts_with('0') {
        digits.drain(..1); // trunk 0
    } else if digits.len() == national + PHONE_COUNTRY_CODE.len() && digits.starts_with(PHONE_COUNTRY_CODE) {
        digits.drain(..PHONE_COUNTRY_CODE.len());
    }
    if digits.len() != national {
        return None;
    }
    let (area, subscriber) = digits.split_at(2);
    (PHONE_AREA_CODES.contains(&area) && subscriber.starts_with(PHONE_MOBILE_PREFIX)).then(|| format!("+{PHONE_COUNTRY_CODE}{area}{subscriber}"))
}

/// An E.164 number formatted for reading (ADR 0191). The first display entry that fits wins; anything else comes back
/// as it is.
#[uniffi::export]
pub fn display_phone(e164: String) -> String {
    let Some(digits) = e164.strip_prefix('+').filter(|d| !d.is_empty() && d.bytes().all(|b| b.is_ascii_digit())) else { return e164 };
    for (country_code, prefix, pattern) in PHONE_DISPLAY {
        let Some(rest) = digits.strip_prefix(country_code) else { continue };
        if !rest.starts_with(prefix) || pattern.chars().filter(|c| *c == '#').count() != rest.len() {
            continue;
        }
        let mut rest_digits = rest.chars();
        let body: String = pattern.chars().map(|c| if c == '#' { rest_digits.next().unwrap_or(c) } else { c }).collect();
        return format!("+{country_code} {body}");
    }
    e164
}

/// A social handle as typed, without surrounding spaces and one leading "@".
#[uniffi::export]
pub fn social_handle_clean(input: String) -> String {
    clean_social(&input).to_owned()
}

/// `None` when the cleaned input is empty or valid. An unknown platform is invalid (fail closed). A handle with the
/// skeleton of a reserved name is refused unless it is the skeleton of the account's own Bipe @.
#[uniffi::export]
pub fn social_handle_problem(platform: String, input: String, own_handle: Option<String>) -> Option<SocialHandleProblem> {
    let handle = clean_social(&input);
    if handle.is_empty() {
        return None;
    }
    let Some(pattern) = social_regex(&platform) else { return Some(SocialHandleProblem::Invalid) };
    if !pattern.is_match(handle) {
        return Some(SocialHandleProblem::Invalid);
    }
    let skeleton = skeleton(handle);
    // JavaScript `ownHandle &&`: an empty own handle counts as none.
    let own = own_handle.filter(|h| !h.is_empty()).is_some_and(|h| self::skeleton(&h) == skeleton);
    (reserved_skeleton(&skeleton) && !own).then_some(SocialHandleProblem::Reserved)
}

/// The link to a social handle: the registry template with the cleaned handle. `None` for an unknown platform.
#[uniffi::export]
pub fn social_url(platform: String, input: String) -> Option<String> {
    let (_, _, url) = SOCIAL_PLATFORMS.iter().find(|(id, _, _)| *id == platform)?;
    Some(url.replace("{handle}", clean_social(&input)))
}

/// Checks a public profile. Lengths count code points, like the API.
#[uniffi::export]
pub fn profile_problem(name: String, bio: String) -> Option<ProfileProblem> {
    let name = js_trim(&name);
    if name.is_empty() {
        return Some(ProfileProblem::NameEmpty);
    }
    if name.chars().count() > PROFILE_NAME_MAX_LENGTH {
        return Some(ProfileProblem::NameTooLong);
    }
    if reserved_category(&normalize(name, TextFold::Names)).is_some() {
        return Some(ProfileProblem::NameReserved);
    }
    let bio = js_trim(&bio);
    if bio.chars().count() > PROFILE_BIO_MAX_LENGTH {
        return Some(ProfileProblem::BioTooLong);
    }
    (bio.split('\n').count() > PROFILE_BIO_MAX_LINES).then_some(ProfileProblem::BioTooManyLines)
}

/// The category of a reserved display name, or `None` when the name is free to use.
#[uniffi::export]
pub fn reserved_name_category(display_name: String) -> Option<String> {
    reserved_category(&normalize(&display_name, TextFold::Names)).map(str::to_owned)
}

/// Security rule 8: the first sealLookalikeEmoji entry in a display name, or `None`. Variation selectors do not count.
#[uniffi::export]
pub fn seal_lookalike(name: String) -> Option<String> {
    let plain = without_variation_selectors(&name);
    SEAL_LOOKALIKE_EMOJI.iter().copied().find(|e| plain.contains(&without_variation_selectors(e))).map(str::to_owned)
}

/// Security rule 8: the first contact name that equals `name` after normalize() with the homoglyph map, as the user
/// saved it. An empty name never collides.
#[uniffi::export]
pub fn contact_collision(name: String, contact_names: Vec<String>) -> Option<String> {
    let n = normalize(&name, TextFold::Names);
    if n.is_empty() {
        return None;
    }
    contact_names.into_iter().find(|c| normalize(c, TextFold::Names) == n)
}

/// ADR 0018: the warning on a saved contact name next to an authenticated verified name, or `None`.
#[uniffi::export]
pub fn relationship_name_warning(saved_name: Option<String>, verified_name: Option<String>, nickname_confirmed: bool) -> Option<RelationshipNameWarning> {
    let saved = normalize(&saved_name?, TextFold::Names);
    let verified = normalize(&verified_name?, TextFold::Names);
    if saved.is_empty() || verified.is_empty() || saved == verified {
        return None;
    }
    let words = saved.split(' ').filter(|w| w.chars().any(is_letter)).count();
    let family = contains_word(&saved, FAMILY_TERMS);
    if words >= RELATIONSHIP_FULL_NAME_MIN_WORDS && !family {
        return Some(RelationshipNameWarning::Mismatch);
    }
    (!nickname_confirmed).then_some(RelationshipNameWarning::Confirm)
}

fn without_variation_selectors(text: &str) -> String {
    text.replace(['\u{FE0E}', '\u{FE0F}'], "")
}

fn skeleton(handle: &str) -> String {
    static STRIP: OnceLock<Option<Regex>> = OnceLock::new();
    let normalized = normalize(handle, TextFold::Names);
    let stripped = match STRIP.get_or_init(|| Regex::new(HANDLE_SKELETON_STRIP).ok()) {
        Some(strip) => strip.replace_all(&normalized, "").into_owned(),
        // Fail closed: without the strip rule nothing is removed, so fewer handles collide. A test checks it compiles.
        None => normalized,
    };
    stripped.chars().filter(|c| !is_js_space(*c)).collect()
}

/// `HANDLE_PATTERN` by hand: `^[a-z0-9](?:[a-z0-9_]|\.(?!\.))*[a-z0-9_]$`. At least two characters of [a-z0-9._],
/// the first [a-z0-9], the last [a-z0-9_], and no two dots in a row. A test pins the pattern text.
fn handle_shape(handle: &str) -> bool {
    let bytes = handle.as_bytes();
    let word = |b: u8| b.is_ascii_lowercase() || b.is_ascii_digit();
    bytes.len() >= 2
        && bytes.iter().all(|&b| word(b) || b == b'_' || b == b'.')
        && word(bytes[0])
        && (word(bytes[bytes.len() - 1]) || bytes[bytes.len() - 1] == b'_')
        && !handle.contains("..")
}

fn owner(handle: &str) -> Option<&'static str> {
    OWNED_HANDLES.iter().find(|(h, _)| *h == handle).map(|(_, owner)| *owner)
}

fn reserved_category(normalized: &str) -> Option<&'static str> {
    RESERVED_NAMES.iter().find(|(name, _)| *name == normalized).map(|(_, category)| *category)
}

/// A reserved name without its spaces: the names are normalised, so their only whitespace is " ".
fn reserved_skeleton(skeleton: &str) -> bool {
    RESERVED_NAMES.iter().any(|(name, _)| name.chars().filter(|c| !is_js_space(*c)).eq(skeleton.chars()))
}

fn clean_social(input: &str) -> &str {
    let trimmed = js_trim(input);
    trimmed.strip_prefix('@').unwrap_or(trimmed)
}

fn social_regex(platform: &str) -> Option<&'static Regex> {
    static COMPILED: OnceLock<Vec<(&'static str, Regex)>> = OnceLock::new();
    let all = COMPILED.get_or_init(|| {
        SOCIAL_PLATFORMS.iter().filter_map(|(id, pattern, _)| Regex::new(pattern).ok().map(|r| (*id, r))).collect()
    });
    all.iter().find(|(id, _)| *id == platform).map(|(_, r)| r)
}

/// The longest prefix of at most `units` UTF-16 units, as JavaScript `slice(0, units)`. A surrogate pair across the
/// cut is dropped whole; JavaScript keeps half of it, which no rule here can match.
fn utf16_prefix(text: &str, units: usize) -> &str {
    let mut count = 0;
    for (byte, c) in text.char_indices() {
        count += c.len_utf16();
        if count > units {
            return &text[..byte];
        }
    }
    text
}

/// JavaScript `/\p{L}/u`: a letter. Rust `is_alphabetic` also takes the letter numbers (Nl) and Other_Alphabetic.
/// Callers remove marks first, and NFKD turns the circled and squared letters into letters, so what is left to take
/// out is Nl and the enclosed letters without a decomposition (U+1F150 to U+1F189, General_Category So).
pub(crate) fn is_letter(c: char) -> bool {
    c.is_alphabetic()
        && !matches!(c,
            '\u{16EE}'..='\u{16F0}' | '\u{2160}'..='\u{2182}' | '\u{2185}'..='\u{2188}' | '\u{3007}' | '\u{3021}'..='\u{3029}'
            | '\u{3038}'..='\u{303A}' | '\u{A6E6}'..='\u{A6EF}' | '\u{10140}'..='\u{10174}' | '\u{10341}' | '\u{1034A}'
            | '\u{103D1}'..='\u{103D5}' | '\u{12400}'..='\u{1246E}' | '\u{1F130}'..='\u{1F189}')
}

/// The handle pattern text and the generated regexes that the core relies on. Tests call it.
#[doc(hidden)]
pub fn identity_patterns_ok() -> bool {
    HANDLE_PATTERN == r"^[a-z0-9](?:[a-z0-9_]|\.(?!\.))*[a-z0-9_]$"
        && Regex::new(HANDLE_SKELETON_STRIP).is_ok()
        && Regex::new(INVITE_CODE_PATTERN).is_ok()
        && SOCIAL_PLATFORMS.iter().all(|(_, p, _)| Regex::new(p).is_ok())
}
