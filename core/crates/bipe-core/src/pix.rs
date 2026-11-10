// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The BR Code part of "Conferir no Bipe" (bipe ADR 0091). Reference: bipe-shared tools/lib/pix.mjs (`pixCheck`,
//! `pixKeyType`, `crc16`). Vectors: bipe-shared test-vectors/pix-check.json, field `code`.
//!
//! The Pix keys in the text and the warning stay in the apps: the key patterns of registries/scam-patterns.json use
//! look-around, and they move with scam matching (ADR 0267, last step).

use crate::text::js_trim;

const PIX_GUI: &str = "br.gov.bcb.pix";

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PixCodeStatus {
    Valid,
    BadCrc,
    Malformed,
}

/// The kind of a Pix key as DICT stores it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PixKeyType {
    Cpf,
    Cnpj,
    Phone,
    Email,
    Random,
    Unknown,
}

/// A BR Code. Every field except `status` is empty or false when the code is malformed.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixCode {
    pub status: PixCodeStatus,
    pub dynamic: bool,
    pub single_use: bool,
    pub key: Option<String>,
    pub key_type: Option<PixKeyType>,
    /// The payload location of a dynamic code, without a scheme.
    pub url: Option<String>,
    pub name: Option<String>,
    pub city: Option<String>,
    /// As written in the code, for example "50.00".
    pub amount: Option<String>,
    pub txid: Option<String>,
    pub info: Option<String>,
}

/// The first BR Code in a text. `start` and `end` count Unicode code points (Swift `unicodeScalars`, Kotlin
/// `codePoints`): the app removes that span before it looks for keys and warnings.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixCodeMatch {
    pub start: u32,
    pub end: u32,
    pub code: PixCode,
}

/// The first BR Code in `text`: it starts at the first "000201" that has br.gov.bcb.pix after it. None when there is
/// no such code.
#[uniffi::export]
pub fn find_pix_code(text: String) -> Option<PixCodeMatch> {
    let points: Vec<char> = text.chars().collect();
    let (start, end, code) = find(&points)?;
    Some(PixCodeMatch { start: start as u32, end: end as u32, code })
}

/// Email has "@", phone is "+" and 12 or 13 digits, random is a UUID, cpf is 11 digits, cnpj is 12 of [0-9A-Z] then
/// 2 digits (format only, no check digits), else unknown.
#[uniffi::export]
pub fn pix_key_type(key: String) -> PixKeyType {
    let digit = |c: char| c.is_ascii_digit();
    let chars: Vec<char> = key.chars().collect();
    if key.contains('@') {
        PixKeyType::Email
    } else if chars.first() == Some(&'+') && matches!(chars.len(), 13 | 14) && chars[1..].iter().all(|&c| digit(c)) {
        PixKeyType::Phone
    } else if is_uuid(&chars) {
        PixKeyType::Random
    } else if chars.len() == 11 && chars.iter().all(|&c| digit(c)) {
        PixKeyType::Cpf
    } else if chars.len() == 14
        && chars[..12].iter().all(|&c| digit(c) || c.is_ascii_uppercase())
        && chars[12..].iter().all(|&c| digit(c))
    {
        PixKeyType::Cnpj
    } else {
        PixKeyType::Unknown
    }
}

/// CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the UTF-8 bytes, as 4 uppercase hex digits.
#[uniffi::export]
pub fn pix_crc16(text: String) -> String {
    format!("{:04X}", crc16(&text))
}

fn crc16(text: &str) -> u16 {
    let mut crc: u16 = 0xFFFF;
    for &byte in text.as_bytes() {
        crc ^= (byte as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 { (crc << 1) ^ 0x1021 } else { crc << 1 };
        }
    }
    crc
}

fn is_uuid(chars: &[char]) -> bool {
    chars.len() == 36
        && chars.iter().enumerate().all(|(i, &c)| if matches!(i, 8 | 13 | 18 | 23) { c == '-' } else { c.is_ascii_hexdigit() })
}

struct Field {
    id: String,
    value: String,
    start: usize,
    end: usize,
}

fn join(points: &[char]) -> String {
    points.iter().collect()
}

/// EMV TLV: two-digit ID, two-digit length, value. Lengths count code points. None when the shape is wrong.
fn read_tlv(points: &[char], from: usize, until: usize) -> Option<Vec<Field>> {
    let mut fields = Vec::new();
    let mut i = from;
    while i < until {
        if i + 4 > until || !points[i..i + 4].iter().all(char::is_ascii_digit) {
            return None;
        }
        let head = join(&points[i..i + 4]);
        let len: usize = head[2..].parse().ok()?;
        if i + 4 + len > until {
            return None;
        }
        let id = head[..2].to_owned();
        let last = id == "63";
        fields.push(Field { id, value: join(&points[i + 4..i + 4 + len]), start: i, end: i + 4 + len });
        i += 4 + len;
        if last {
            break;
        }
    }
    Some(fields)
}

fn subfields(value: &str) -> Option<Vec<(String, String)>> {
    let points: Vec<char> = value.chars().collect();
    Some(read_tlv(&points, 0, points.len())?.into_iter().map(|f| (f.id, f.value)).collect())
}

fn get<'a>(fields: &'a [(String, String)], id: &str) -> Option<&'a str> {
    // The reference keeps the last value of a repeated ID (a Map built in order).
    fields.iter().rev().find(|(i, _)| i == id).map(|(_, v)| v.as_str())
}


fn malformed() -> PixCode {
    PixCode {
        status: PixCodeStatus::Malformed,
        dynamic: false,
        single_use: false,
        key: None,
        key_type: None,
        url: None,
        name: None,
        city: None,
        amount: None,
        txid: None,
        info: None,
    }
}

fn find(points: &[char]) -> Option<(usize, usize, PixCode)> {
    let start = (0..points.len().checked_sub(5)?).find(|&s| join(&points[s..s + 6]) == "000201")?;
    if !join(&points[start..]).to_ascii_lowercase().contains(PIX_GUI) {
        return None;
    }
    let Some(fields) = read_tlv(points, start, points.len()) else {
        return Some((start, points.len(), malformed()));
    };
    let end = fields.last().map_or(points.len(), |f| f.end);
    Some((start, end, parse(points, start, &fields)))
}

fn parse(points: &[char], start: usize, fields: &[Field]) -> PixCode {
    let crc_field = &fields[fields.len() - 1];
    if fields[0].id != "00" || fields[0].value != "01" {
        return malformed();
    }
    // `.length` of the reference: UTF-16 units.
    if crc_field.id != "63" || crc_field.value.encode_utf16().count() != 4 {
        return malformed();
    }
    let by_id: Vec<(String, String)> = fields.iter().map(|f| (f.id.clone(), f.value.clone())).collect();

    let account = fields.iter().find_map(|f| {
        let n: u32 = f.id.parse().ok()?;
        if !(26..=51).contains(&n) {
            return None;
        }
        let subs = subfields(&f.value)?;
        (get(&subs, "00").unwrap_or("").to_ascii_lowercase() == PIX_GUI).then_some(subs)
    });
    let Some(account) = account else { return malformed() };
    // An empty value counts as missing, as in the reference.
    let key = get(&account, "01").filter(|k| !k.is_empty());
    let url = get(&account, "25");
    if key.is_none() && url.is_none_or(str::is_empty) {
        return malformed();
    }

    let mut txid = None;
    if let Some(extra) = get(&by_id, "62").filter(|e| !e.is_empty()) {
        let Some(subs) = subfields(extra) else { return malformed() };
        txid = subs.iter().find(|(id, _)| id == "05").map(|(_, v)| v.clone()).filter(|t| !t.is_empty() && t != "***");
    }

    let signed = join(&points[start..crc_field.start + 4]);
    let key = key.map(|k| js_trim(k).to_owned());
    PixCode {
        status: if format!("{:04X}", crc16(&signed)) == crc_field.value.to_uppercase() {
            PixCodeStatus::Valid
        } else {
            PixCodeStatus::BadCrc
        },
        dynamic: url.is_some(),
        single_use: get(&by_id, "01") == Some("12"),
        key_type: key.clone().map(pix_key_type),
        key,
        url: url.map(str::to_owned),
        name: get(&by_id, "59").map(str::to_owned),
        city: get(&by_id, "60").map(str::to_owned),
        amount: get(&by_id, "54").map(str::to_owned),
        txid,
        info: get(&account, "02").map(str::to_owned),
    }
}
