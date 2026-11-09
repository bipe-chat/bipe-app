// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The CVM lines of the company seal detail (bipe ADR 0077 point 3, bipe-chat/bipe#238): the signed badge field `cvm`
//! (docs/badge-attestation.md) to the lines the detail shows. Fail closed: a field that is missing or not well formed
//! shows no line; the badge itself stays valid. Reference: bipe-shared tools/lib/cvm.mjs. Vectors:
//! test-vectors/cvm-lines.json.

use serde_json::Value;

use crate::pix::js_trim;

const MAX_CATEGORIES: usize = 10;
/// In UTF-16 units, as the reference's `.length`.
const MAX_CATEGORY_LENGTH: usize = 120;

/// The string key of a line. The app shows the bipe-shared string of that key.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum CvmLineKey {
    /// bipe_cvm_registered, with a category.
    Registered,
    /// bipe_cvm_cancelled, with a date.
    Cancelled,
    /// bipe_cvm_no_registration.
    NoRegistration,
    /// bipe_cvm_bank_note.
    BankNote,
    /// bipe_cvm_stop_order, with a date.
    StopOrder,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CvmLine {
    pub key: CvmLineKey,
    /// A category, or a date as DD/MM/AAAA.
    pub arg: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct CvmLines {
    pub lines: Vec<CvmLine>,
    /// A stop order: the line is red and chats from the account show bipe_cvm_stop_order_card.
    pub danger: bool,
}

/// The lines of the `cvm` field, given as JSON text. None, or text that is not a JSON object, shows nothing.
#[uniffi::export]
pub fn cvm_lines(field_json: Option<String>) -> CvmLines {
    let none = CvmLines { lines: vec![], danger: false };
    let Some(Ok(Value::Object(field))) = field_json.map(|text| serde_json::from_str::<Value>(&text)) else {
        return none;
    };
    let line = |key, arg| CvmLine { key, arg };
    match field.get("st").and_then(Value::as_str) {
        Some("stop_order") => match display_date(field.get("on")) {
            Some(on) => CvmLines { lines: vec![line(CvmLineKey::StopOrder, Some(on))], danger: true },
            None => none,
        },
        Some("registered") => {
            let Some(cat) = field.get("cat").and_then(Value::as_array) else { return none };
            if cat.is_empty() || cat.len() > MAX_CATEGORIES {
                return none;
            }
            let mut seen: Vec<String> = Vec::new();
            for c in cat {
                let Some(c) = c.as_str() else { return none };
                if js_trim(c).is_empty() || c.encode_utf16().count() > MAX_CATEGORY_LENGTH {
                    return none;
                }
                let c = js_trim(c).to_owned();
                if !seen.contains(&c) {
                    seen.push(c);
                }
            }
            CvmLines { lines: seen.into_iter().map(|c| line(CvmLineKey::Registered, Some(c))).collect(), danger: false }
        }
        Some("cancelled") => match display_date(field.get("on")) {
            Some(on) => CvmLines { lines: vec![line(CvmLineKey::Cancelled, Some(on))], danger: false },
            None => none,
        },
        Some("no_registration") => {
            CvmLines { lines: vec![line(CvmLineKey::NoRegistration, None), line(CvmLineKey::BankNote, None)], danger: false }
        }
        _ => none,
    }
}

/// YYYY-MM-DD (month 01 to 12, day 01 to 31, no calendar check) to DD/MM/AAAA, else None.
fn display_date(value: Option<&Value>) -> Option<String> {
    let text = value?.as_str()?;
    let b = text.as_bytes();
    let digits = |r: std::ops::Range<usize>| b[r].iter().all(u8::is_ascii_digit);
    if b.len() != 10 || b[4] != b'-' || b[7] != b'-' || !digits(0..4) || !digits(5..7) || !digits(8..10) {
        return None;
    }
    let month: u8 = text[5..7].parse().ok()?;
    let day: u8 = text[8..10].parse().ok()?;
    ((1..=12).contains(&month) && (1..=31).contains(&day)).then(|| format!("{}/{}/{}", &text[8..10], &text[5..7], &text[..4]))
}
