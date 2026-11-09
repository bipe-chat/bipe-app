// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Pix request marks (bipe ADR 0046, bipe-chat/bipe#130; registries/pix-marks.json): the payer marks "enviei", the
//! requester marks "recebi" or "não recebi". Bipe moves no money (ADR 0012); a line is paid only after "recebi".
//! Reference: `pixMarkLines`, `pixTxid` and `pixE2eIdValid` in bipe-shared tools/lib/pix.mjs. Vectors:
//! test-vectors/pix-marks.json.

use std::cmp::Ordering;

use serde_json::{Map, Value, json};

use crate::generated::registries::{PIX_MARK_EVENT_TYPE, PIX_MARK_REL_TYPE, PIX_MARKS, PIX_TXID_MAX_LENGTH};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PixMarkState {
    Issued,
    Sent,
    Received,
    NotReceived,
}

/// The marks a person can send: every state but issued.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PixMark {
    Sent,
    Received,
    NotReceived,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixMarkCard {
    pub event_id: String,
    /// The card's sender.
    pub requester: String,
    pub payers: Vec<String>,
}

/// A mark event: its ID, sender, origin_server_ts and content as JSON text.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixMarkEvent {
    pub event_id: String,
    pub sender: String,
    pub ts: i64,
    pub content_json: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixMarkHistoryEntry {
    pub event_id: String,
    pub mark: PixMark,
}

/// One payer's line of the card. `e2e_id` is the payer's claim from the bank receipt; Bipe cannot check it.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixMarkLine {
    pub payer: String,
    pub state: PixMarkState,
    pub e2e_id: Option<String>,
    /// True only in state received.
    pub paid: bool,
    pub history: Vec<PixMarkHistoryEntry>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PixMarkLines {
    /// One line per payer, in the card's payer order. A payer listed twice has one line.
    pub lines: Vec<PixMarkLine>,
    /// The event IDs of marks that break a rule. They change no line.
    pub rejected: Vec<String>,
}

/// The lines of a request card from its mark events, read in `ts` order, ties by event ID.
#[uniffi::export]
pub fn pix_mark_lines(card: PixMarkCard, events: Vec<PixMarkEvent>) -> PixMarkLines {
    let mut lines: Vec<PixMarkLine> = Vec::new();
    for payer in &card.payers {
        if !lines.iter().any(|l| &l.payer == payer) {
            lines.push(PixMarkLine { payer: payer.clone(), state: PixMarkState::Issued, e2e_id: None, paid: false, history: vec![] });
        }
    }
    let mut ordered: Vec<&PixMarkEvent> = events.iter().collect();
    // JavaScript compares strings by UTF-16 code units.
    ordered.sort_by(|a, b| a.ts.cmp(&b.ts).then_with(|| utf16_cmp(&a.event_id, &b.event_id)));
    let mut rejected = Vec::new();
    for event in ordered {
        let Some((index, mark, e2e_id)) = accepted(&card, event, &lines) else {
            rejected.push(event.event_id.clone());
            continue;
        };
        let line = &mut lines[index];
        line.state = state_of(mark);
        if mark == PixMark::Sent {
            line.e2e_id = e2e_id;
        }
        line.paid = mark == PixMark::Received;
        line.history.push(PixMarkHistoryEntry { event_id: event.event_id.clone(), mark });
    }
    PixMarkLines { lines, rejected }
}

/// The BR Code txid of a request card: the request ID with ASCII letters and digits only, at most txidMaxLength.
#[uniffi::export]
pub fn pix_txid(request_id: String) -> String {
    request_id.chars().filter(char::is_ascii_alphanumeric).take(PIX_TXID_MAX_LENGTH).collect()
}

/// The format of a Pix end-to-end ID (`e2eIdPattern`): E, the 8-digit ISPB, 23 letters or digits. The app cannot check
/// that the payment exists.
#[uniffi::export]
pub fn pix_e2e_id_valid(id: String) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 32
        && bytes[0] == b'E'
        && bytes[1..9].iter().all(u8::is_ascii_digit)
        && bytes[9..].iter().all(u8::is_ascii_alphanumeric)
}

/// The event type of a mark (`eventType`), for the app's raw send.
#[uniffi::export]
pub fn pix_mark_event_type() -> String {
    PIX_MARK_EVENT_TYPE.to_owned()
}

/// The content of a mark event, as JSON text for the app's raw send. None when an E2E ID is given on another mark
/// than sent, or does not have the format.
#[uniffi::export]
pub fn pix_mark_content(card_event_id: String, mark: PixMark, payer: Option<String>, e2e_id: Option<String>) -> Option<String> {
    if let Some(id) = &e2e_id
        && (mark != PixMark::Sent || !pix_e2e_id_valid(id.clone()))
    {
        return None;
    }
    let mut content = Map::new();
    content.insert("m.relates_to".into(), json!({ "rel_type": PIX_MARK_REL_TYPE, "event_id": card_event_id }));
    content.insert("mark".into(), json!(mark_id(mark)));
    if let Some(payer) = payer {
        content.insert("payer".into(), json!(payer));
    }
    if let Some(id) = e2e_id {
        content.insert("e2e_id".into(), json!(id));
    }
    Some(Value::Object(content).to_string())
}

fn utf16_cmp(a: &str, b: &str) -> Ordering {
    a.encode_utf16().cmp(b.encode_utf16())
}

fn mark_id(mark: PixMark) -> &'static str {
    match mark {
        PixMark::Sent => PIX_MARKS[0],
        PixMark::Received => PIX_MARKS[1],
        PixMark::NotReceived => PIX_MARKS[2],
    }
}

fn mark_of(id: &str) -> Option<PixMark> {
    [PixMark::Sent, PixMark::Received, PixMark::NotReceived].into_iter().find(|&m| mark_id(m) == id)
}

fn state_of(mark: PixMark) -> PixMarkState {
    match mark {
        PixMark::Sent => PixMarkState::Sent,
        PixMark::Received => PixMarkState::Received,
        PixMark::NotReceived => PixMarkState::NotReceived,
    }
}

/// The states a line may be in for each mark.
fn allowed_from(mark: PixMark, state: PixMarkState) -> bool {
    use PixMarkState::*;
    match mark {
        PixMark::Sent | PixMark::Received => matches!(state, Issued | Sent | NotReceived),
        PixMark::NotReceived => matches!(state, Sent | Received),
    }
}

/// The line index, mark and E2E ID of an event that keeps every rule; None when it breaks one.
fn accepted(card: &PixMarkCard, event: &PixMarkEvent, lines: &[PixMarkLine]) -> Option<(usize, PixMark, Option<String>)> {
    let content: Map<String, Value> = match serde_json::from_str(&event.content_json) {
        Ok(Value::Object(map)) => map,
        _ => return None,
    };
    let relation = content.get("m.relates_to")?.as_object()?;
    if relation.get("rel_type")?.as_str()? != PIX_MARK_REL_TYPE || relation.get("event_id")?.as_str()? != card.event_id {
        return None;
    }
    let mark = mark_of(content.get("mark")?.as_str()?)?;
    let named_payer = match content.get("payer") {
        None => None,
        Some(Value::String(payer)) => Some(payer.as_str()),
        Some(_) => return None,
    };
    let e2e_id = match content.get("e2e_id") {
        None => None,
        Some(Value::String(id)) if mark == PixMark::Sent && pix_e2e_id_valid(id.clone()) => Some(id.clone()),
        Some(_) => return None,
    };
    let payer = if mark == PixMark::Sent {
        if named_payer.is_some_and(|p| p != event.sender) {
            return None;
        }
        event.sender.as_str()
    } else {
        if event.sender != card.requester {
            return None;
        }
        match named_payer {
            Some(payer) => payer,
            None if lines.len() == 1 => lines[0].payer.as_str(),
            None => return None,
        }
    };
    let index = lines.iter().position(|l| l.payer == payer)?;
    allowed_from(mark, lines[index].state).then_some((index, mark, e2e_id))
}
