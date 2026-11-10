// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The profile fact lines of chat-request and person-profile and the amber notice of chat-timeline (ADR 0049).
//! Reference: bipe-shared tools/lib/registries.mjs `profileFactLines`. Vectors: test-vectors/profile-facts.json.

use crate::generated::registries::{
    PROFILE_FACTS_NEW_ACCOUNT_DAYS, PROFILE_FACTS_NOTICE_DAYS, PROFILE_FACTS_NOTICE_PAIR_HOURS, PROFILE_FACTS_WINDOW_DAYS,
};

const DAY_MS: i64 = 86_400_000;
const HOUR_MS: i64 = 3_600_000;

/// The facts the API serves (POST /v1/profile-facts): ISO 8601 times.
#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct ProfileFacts {
    pub created_at: Option<String>,
    pub name_changes: Vec<String>,
    pub photo_changes: Vec<String>,
    pub number_moved_out: Vec<String>,
    pub number_moved_in: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ProfileFact {
    Created,
    Photo,
    Name,
    NumberOut,
    NumberIn,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum NumberNotice {
    Out,
    In,
}

/// One line. `count` is the number of changes in the window, absent for created and the number moves. `at_ms` is the
/// creation time or the latest change, in Unix milliseconds.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ProfileFactLine {
    pub fact: ProfileFact,
    pub count: Option<u32>,
    pub at_ms: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ProfileFactLines {
    /// In order: created, photo, name, numberOut, numberIn. Empty when nothing applies.
    pub lines: Vec<ProfileFactLine>,
    /// A name change and a photo change at most noticePairHours apart, the later one younger than noticeDays.
    pub notice: bool,
    /// The newest number move in the window, absent without one.
    pub number_notice: Option<NumberNotice>,
}

/// The lines for these facts at `now_ms`. `always_created`: person-profile keeps the account age at any age
/// (ADR 0275 point 13). A time that is not an ISO 8601 time with a zone counts as absent.
#[uniffi::export]
pub fn profile_fact_lines(facts: ProfileFacts, now_ms: i64, always_created: bool) -> ProfileFactLines {
    let in_window = |list: &[String]| -> Vec<i64> {
        let mut times: Vec<i64> =
            list.iter().filter_map(|t| parse_iso_time(t)).filter(|&t| t <= now_ms && now_ms - t < PROFILE_FACTS_WINDOW_DAYS * DAY_MS).collect();
        times.sort_unstable();
        times
    };
    let names = in_window(&facts.name_changes);
    let photos = in_window(&facts.photo_changes);
    let mut lines = Vec::new();
    if let Some(created) = facts.created_at.as_deref().and_then(parse_iso_time)
        && created <= now_ms
        && (always_created || now_ms - created < PROFILE_FACTS_NEW_ACCOUNT_DAYS * DAY_MS)
    {
        lines.push(ProfileFactLine { fact: ProfileFact::Created, count: None, at_ms: created });
    }
    if let Some(&last) = photos.last() {
        lines.push(ProfileFactLine { fact: ProfileFact::Photo, count: Some(photos.len() as u32), at_ms: last });
    }
    if let Some(&last) = names.last() {
        lines.push(ProfileFactLine { fact: ProfileFact::Name, count: Some(names.len() as u32), at_ms: last });
    }
    let moved_out = in_window(&facts.number_moved_out).last().copied();
    let moved_in = in_window(&facts.number_moved_in).last().copied();
    if let Some(at) = moved_out {
        lines.push(ProfileFactLine { fact: ProfileFact::NumberOut, count: None, at_ms: at });
    }
    if let Some(at) = moved_in {
        lines.push(ProfileFactLine { fact: ProfileFact::NumberIn, count: None, at_ms: at });
    }
    let notice = names.iter().any(|&n| {
        photos.iter().any(|&p| (n - p).abs() <= PROFILE_FACTS_NOTICE_PAIR_HOURS * HOUR_MS && now_ms - n.max(p) < PROFILE_FACTS_NOTICE_DAYS * DAY_MS)
    });
    let number_notice = (moved_out.is_some() || moved_in.is_some())
        .then(|| if moved_in.unwrap_or(-1) > moved_out.unwrap_or(-1) { NumberNotice::In } else { NumberNotice::Out });
    ProfileFactLines { lines, notice, number_notice }
}

/// An ISO 8601 time with a zone (the JavaScript Date Time String Format) in Unix milliseconds, or `None`.
/// Takes YYYY-MM-DD, optionally followed by THH:mm, THH:mm:ss or THH:mm:ss.fraction and Z or ±HH:mm. A date alone is
/// UTC. A time without a zone is local time in JavaScript, which differs per device, so it counts as absent.
#[doc(hidden)]
pub fn parse_iso_time(text: &str) -> Option<i64> {
    let b = text.as_bytes();
    let num = |range: std::ops::Range<usize>| -> Option<i64> {
        let part = b.get(range)?;
        part.iter().all(u8::is_ascii_digit).then(|| part.iter().fold(0i64, |n, d| n * 10 + i64::from(d - b'0')))
    };
    let year = num(0..4)?;
    if b.get(4) != Some(&b'-') || b.get(7) != Some(&b'-') {
        return None;
    }
    let (month, day) = (num(5..7)?, num(8..10)?);
    if !(1..=12).contains(&month) || day < 1 || day > days_in_month(year, month) {
        return None;
    }
    let days = days_from_civil(year, month, day);
    if b.len() == 10 {
        return Some(days * DAY_MS);
    }
    if b.get(10) != Some(&b'T') || b.get(13) != Some(&b':') {
        return None;
    }
    let (hour, minute) = (num(11..13)?, num(14..16)?);
    let mut i = 16;
    let (mut second, mut millis) = (0, 0);
    if b.get(i) == Some(&b':') {
        second = num(i + 1..i + 3)?;
        i += 3;
        if b.get(i) == Some(&b'.') {
            let digits = b[i + 1..].iter().take_while(|c| c.is_ascii_digit()).count();
            if digits == 0 {
                return None;
            }
            // Milliseconds: the first three digits; JavaScript drops the rest.
            let first3 = num(i + 1..i + 1 + digits.min(3))?;
            millis = first3 * 10i64.pow(3 - digits.min(3) as u32);
            i += 1 + digits;
        }
    }
    let offset_minutes = match b.get(i) {
        Some(b'Z') if b.len() == i + 1 => 0,
        Some(&sign @ (b'+' | b'-')) if b.len() == i + 6 && b[i + 3] == b':' => {
            let (oh, om) = (num(i + 1..i + 3)?, num(i + 4..i + 6)?);
            if oh > 23 || om > 59 {
                return None;
            }
            let total = oh * 60 + om;
            if sign == b'+' { total } else { -total }
        }
        _ => return None,
    };
    let end_of_day = hour == 24 && minute == 0 && second == 0 && millis == 0;
    if !(hour < 24 || end_of_day) || minute > 59 || second > 59 {
        return None;
    }
    Some(days * DAY_MS + hour * HOUR_MS + minute * 60_000 + second * 1000 + millis - offset_minutes * 60_000)
}

/// JavaScript `toISOString()` of Unix milliseconds: YYYY-MM-DDTHH:mm:ss.sssZ, for years 0 to 9999.
#[doc(hidden)]
pub fn iso_time(ms: i64) -> String {
    let days = ms.div_euclid(DAY_MS);
    let rest = ms.rem_euclid(DAY_MS);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.{:03}Z",
        rest / HOUR_MS,
        rest % HOUR_MS / 60_000,
        rest % 60_000 / 1000,
        rest % 1000
    )
}

fn is_leap(year: i64) -> bool {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

fn days_in_month(year: i64, month: i64) -> i64 {
    match month {
        2 if is_leap(year) => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    }
}

/// Days since 1970-01-01 (proleptic Gregorian).
fn days_from_civil(year: i64, month: i64, day: i64) -> i64 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (yoe + era * 400 + i64::from(month <= 2), month, day)
}
