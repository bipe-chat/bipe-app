// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Onboarding schedules, the remote banner, sticker search and the PIN check. Reference: bipe-shared
//! tools/lib/registries.mjs. Vectors: onboarding-schedule.json, remote-banner.json, sticker-search.json, pins.json.

use crate::generated::registries::*;
use crate::identity::is_letter;
use crate::profile_facts::parse_iso_time;
use crate::text::{js_trim, utf16_len};
use unicode_normalization::UnicodeNormalization;
use unicode_normalization::char::is_combining_mark;

const DAY_MS: i64 = 86_400_000;
const HOUR_MS: i64 = 3_600_000;

// --- Onboarding ----------------------------------------------------------------------------------------------------

/// The result of a PIN reminder check.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PinCheckResult {
    Right,
    Wrong,
    /// "Agora não": the step stays and the reminder comes back after 1 day.
    Later,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PinReminder {
    pub step: u32,
    pub gap_days: u32,
}

/// The PIN reminder after a check (bipe-chat/bipe#202): the next step and the days until the next reminder.
#[uniffi::export]
pub fn pin_reminder_next(step: u32, result: PinCheckResult) -> PinReminder {
    let gap = |n: u32| PIN_REMINDER_DAYS.get(n as usize).copied().unwrap_or(PIN_REMINDER_THEN_EVERY_DAYS);
    match result {
        PinCheckResult::Later => PinReminder { step, gap_days: 1 },
        PinCheckResult::Right => PinReminder { step: step + 1, gap_days: gap(step + 1) },
        PinCheckResult::Wrong => PinReminder { step: 0, gap_days: gap(0) },
    }
}

/// One invite slot of the bring-people sheet: the invite status (pending, used, expired, revoked) and its created_at.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct InviteSlot {
    pub status: String,
    pub sent_at_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BringPeopleState {
    pub declines: u32,
    /// The last ask (the sheet or a reminder), absent when never asked.
    pub last_ask_at_ms: Option<i64>,
    pub slots: Vec<InviteSlot>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum InviteSlotKind {
    Pending,
    Expired,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BringPeopleReminder {
    pub slot: u32,
    pub kind: InviteSlotKind,
}

/// The bring-people reminder (bipe-chat/bipe#752): the slot to name, or `None` for no reminder now. The oldest
/// qualifying slot wins; on a tie, the first in slot order.
#[uniffi::export]
pub fn bring_people_reminder(state: BringPeopleState, now_ms: i64) -> Option<BringPeopleReminder> {
    if state.declines >= BRING_PEOPLE_STOP_AFTER_DECLINES {
        return None;
    }
    let last = state.last_ask_at_ms?;
    if now_ms - last < BRING_PEOPLE_FOLLOW_UP_EVERY_DAYS * DAY_MS {
        return None;
    }
    let mut best: Option<(usize, InviteSlotKind)> = None;
    for (i, s) in state.slots.iter().enumerate() {
        let kind = match s.status.as_str() {
            "expired" => Some(InviteSlotKind::Expired),
            "pending" if now_ms - s.sent_at_ms >= BRING_PEOPLE_PENDING_AFTER_DAYS * DAY_MS => Some(InviteSlotKind::Pending),
            _ => None,
        };
        if let Some(kind) = kind
            && best.is_none_or(|(b, _)| s.sent_at_ms < state.slots[b].sent_at_ms)
        {
            best = Some((i, kind));
        }
    }
    best.map(|(slot, kind)| BringPeopleReminder { slot: slot as u32, kind })
}

/// The holder of the shared prompt slot and when it took the slot.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PromptSlot {
    pub holder: String,
    pub since_ms: i64,
}

/// The chat list prompt card (bipe-chat/bipe#894): the key that shows, or `None`. A critical or access entry wins at
/// once; any other shows only when the slot allows it. The first allowed entry in queue order wins.
#[uniffi::export]
pub fn chat_list_prompt(eligible: Vec<String>, slot: Option<PromptSlot>, now_ms: i64) -> Option<String> {
    let entries: Vec<&(&str, &str)> = CHAT_LIST_PROMPTS.iter().filter(|(key, _)| eligible.iter().any(|e| e == key)).collect();
    if let Some((key, _)) = entries.iter().find(|(_, tier)| *tier == "critical" || *tier == "access") {
        return Some((*key).to_owned());
    }
    let allows = |key: &str| match &slot {
        None => true,
        Some(s) => s.holder == key || now_ms - s.since_ms >= PROMPT_SLOT_HOURS * HOUR_MS,
    };
    entries.iter().find(|(key, _)| allows(key)).map(|(key, _)| (*key).to_owned())
}

// --- Remote banner -------------------------------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct RemoteBanner {
    pub expires_at: String,
    pub min_app_version: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum RemoteBannerState {
    Hidden,
    /// The app is below min_app_version: the banner opens the update link and cannot be dismissed.
    Update,
    Notice,
}

/// Compares dotted app versions part by part as numbers: -1, 0 or 1. Each part counts its leading digits only; a
/// missing part or a part without leading digits counts as 0.
#[uniffi::export]
pub fn compare_app_versions(a: String, b: String) -> i32 {
    let part = |p: Option<&str>| -> f64 {
        let digits: String = p.unwrap_or("").chars().take_while(char::is_ascii_digit).collect();
        digits.parse::<f64>().unwrap_or(0.0)
    };
    let (pa, pb): (Vec<&str>, Vec<&str>) = (a.split('.').collect(), b.split('.').collect());
    for i in 0..pa.len().max(pb.len()) {
        let (x, y) = (part(pa.get(i).copied()), part(pb.get(i).copied()));
        if x != y {
            return if x < y { -1 } else { 1 };
        }
    }
    0
}

/// What the app shows for the banner of GET /v1/banner at `now` (ISO 8601). A time that does not parse never hides
/// the banner, as in JavaScript, where NaN compares false.
#[uniffi::export]
pub fn remote_banner_state(banner: Option<RemoteBanner>, app_version: String, now: String) -> RemoteBannerState {
    let Some(banner) = banner else { return RemoteBannerState::Hidden };
    if let (Some(expires), Some(now)) = (parse_iso_time(&banner.expires_at), parse_iso_time(&now))
        && expires <= now
    {
        return RemoteBannerState::Hidden;
    }
    match banner.min_app_version.filter(|v| !v.is_empty()) {
        Some(min) if compare_app_versions(app_version, min) < 0 => RemoteBannerState::Update,
        _ => RemoteBannerState::Notice,
    }
}

// --- Sticker search ------------------------------------------------------------------------------------------------

/// NFKD, no marks, lower case, every run that is not a letter or a digit is one space, every run of one repeated
/// letter is one letter (kkkk is k), spaces collapsed and trimmed.
#[uniffi::export]
pub fn sticker_search_fold(text: String) -> String {
    fold(&text)
}

/// An emoji query without variation selectors (U+FE0F) and skin tones (U+1F3FB to U+1F3FF), trimmed.
#[uniffi::export]
pub fn sticker_emoji_key(text: String) -> String {
    emoji_key(&text)
}

/// The tag ids a query asks for, in registry order. An emoji query maps through the emoji table; a word query through
/// the word table and the tag ids: equal, or a prefix when the folded query is long enough.
#[uniffi::export]
pub fn sticker_query_tags(query: String) -> Vec<String> {
    query_tags(&query).into_iter().map(str::to_owned).collect()
}

/// Whether a sticker matches the query: a shared tag, or the folded query at the start of a word of the folded body or
/// pack name.
#[uniffi::export]
pub fn sticker_matches(query: String, body: Option<String>, tags: Vec<String>, pack: Option<String>) -> bool {
    let wanted = query_tags(&query);
    if tags.iter().any(|t| wanted.contains(&t.as_str())) {
        return true;
    }
    let q = fold(&query);
    if q.is_empty() {
        return false;
    }
    let needle = format!(" {q}");
    [body, pack].iter().any(|text| format!(" {}", fold(text.as_deref().unwrap_or(""))).contains(&needle))
}

fn query_tags(query: &str) -> Vec<&'static str> {
    let key = emoji_key(query);
    let emoji = STICKER_EMOJI.iter().find(|(e, _)| *e == key).map(|(_, tags)| *tags);
    let mut found: Vec<&str> = emoji.map(<[&str]>::to_vec).unwrap_or_default();
    let q = fold(query);
    if emoji.is_none() && !q.is_empty() {
        let hits = |word: &str| word == q || (utf16_len(&q) >= STICKER_PREFIX_MIN_LENGTH && word.starts_with(&q));
        for (word, tags) in STICKER_WORDS {
            if hits(word) {
                found.extend_from_slice(tags);
            }
        }
        for tag in STICKER_TAGS {
            if hits(&fold(tag)) {
                found.push(tag);
            }
        }
    }
    STICKER_TAGS.iter().copied().filter(|t| found.contains(t)).collect()
}

fn fold(text: &str) -> String {
    let lowered: String = text.nfkd().filter(|c| !is_combining_mark(*c)).collect::<String>().to_lowercase();
    // [^\p{L}\p{N}]+ -> " ", then (\p{L})\1+ -> $1, then " +" -> " ", then trim.
    let mut out = String::with_capacity(lowered.len());
    let mut last: Option<char> = None;
    let mut in_gap = false;
    for c in lowered.chars() {
        if is_letter(c) || c.is_numeric() {
            if !(is_letter(c) && last == Some(c)) {
                out.push(c);
            }
            last = Some(c);
            in_gap = false;
        } else if !in_gap {
            out.push(' ');
            last = Some(' ');
            in_gap = true;
        }
    }
    js_trim(&out).to_owned()
}

fn emoji_key(text: &str) -> String {
    let stripped: String = text.chars().filter(|c| *c != '\u{FE0F}' && !('\u{1F3FB}'..='\u{1F3FF}').contains(c)).collect();
    js_trim(&stripped).to_owned()
}

// --- PIN check -----------------------------------------------------------------------------------------------------

/// Why a PIN is too weak (ADR 0057). The ids are shared by every platform.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PinProblem {
    NotDigits,
    WrongLength,
    RepeatedDigit,
    /// Up or down by one, with wrap: 78901234 and 21098765 count.
    Sequence,
    /// DDMMYYYY or YYYYMMDD of a real date in the registry's years.
    Date,
}

/// The PIN check of what the user typed: `None` when the PIN is strong enough.
#[uniffi::export]
pub fn pin_problem(pin: String) -> Option<PinProblem> {
    if !pin.bytes().all(|b| b.is_ascii_digit()) {
        return Some(PinProblem::NotDigits);
    }
    if pin.len() != PIN_LENGTH {
        return Some(PinProblem::WrongLength);
    }
    let d: Vec<u32> = pin.bytes().map(|b| u32::from(b - b'0')).collect();
    if d.iter().all(|&x| x == d[0]) {
        return Some(PinProblem::RepeatedDigit);
    }
    for step in [1, 9] {
        if d.windows(2).all(|w| w[1] == (w[0] + step) % 10) {
            return Some(PinProblem::Sequence);
        }
    }
    let n = |from: usize, to: usize| pin.get(from..to).and_then(|s| s.parse::<u32>().ok()).unwrap_or(0);
    // The date checks read fixed places, so they need the 8-digit layout.
    if PIN_LENGTH == 8 && (is_date(n(4, 8), n(2, 4), n(0, 2)) || is_date(n(0, 4), n(4, 6), n(6, 8))) {
        return Some(PinProblem::Date);
    }
    None
}

fn is_date(year: u32, month: u32, day: u32) -> bool {
    if !(PIN_DATE_YEAR_MIN..=PIN_DATE_YEAR_MAX).contains(&year) || !(1..=12).contains(&month) || day < 1 {
        return false;
    }
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days = [31, if leap { 29 } else { 28 }, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month as usize - 1];
    day <= days
}
