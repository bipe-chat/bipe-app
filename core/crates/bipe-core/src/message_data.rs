// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Rules on Matrix event content: forward marks, cleared chats, assistant send, drafts and work notes.
//! Reference: bipe-shared tools/lib/registries.mjs. Vectors: forward-marks.json, cleared-chats.json,
//! assistant-send.json, drafts.json, work-notes.json. Content goes in and out as JSON text, so unknown keys stay.

use crate::generated::registries::*;
use crate::text::{js_trim, utf16_len};
use serde_json::{Map, Value, json};

/// The largest forward count an app keeps (FORWARD_COUNT_MAX). A larger count reads as this one.
const FORWARD_COUNT_MAX: f64 = 2_147_483_647.0;

/// The forward line of a message (bipe-chat/bipe#933).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ForwardLine {
    Forwarded,
    ForwardedManyTimes,
}

/// The source line of a voice message or an audio file.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum VoiceSource {
    Forwarded,
    ForwardedManyTimes,
    Recorded,
    File,
}

/// A draft of the drafts room (ADR 0210, bipe-chat/bipe#866).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AssistantDraft {
    pub room_id: String,
    pub body: String,
    pub reply_to: Option<String>,
    pub thread_root: Option<String>,
    pub client: String,
}

/// A note on a customer chat (ADR 0284). `seat_id` is absent when the note names no seat of the account.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct WorkNote {
    pub room_id: String,
    pub body: String,
    pub seat_id: Option<String>,
}

/// Who sent a reply to a customer (ADR 0284 point 19).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ReplyBy {
    pub room_id: String,
    pub event_id: String,
    pub seat_id: String,
}

/// A drafts room event as a draft, or the reason it does not show (the reference reason ids).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum DraftResult {
    Show { draft: AssistantDraft },
    Hide { reason: String },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum WorkNoteResult {
    Show { note: WorkNote },
    Hide { reason: String },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ReplyByResult {
    Show { reply_by: ReplyBy },
    Hide { reason: String },
}

// --- Forward marks -------------------------------------------------------------------------------------------------

/// The forward count of message content: content[forwardKey].count when it is an integer of 1 or more, else 0.
#[uniffi::export]
pub fn forward_count(content_json: String) -> u32 {
    count_of(object(Some(&content_json)).as_ref())
}

/// The content a forward sends: the latest edit's m.new_content, else the original, without m.relates_to,
/// m.new_content, m.mentions and the public feed mark, with forwardKey set to the original count plus 1.
/// `None` when the content is not a JSON object.
#[uniffi::export]
pub fn forwarded_content(original_json: String, new_content_json: Option<String>) -> Option<String> {
    let original = object(Some(&original_json))?;
    let mut base = object(new_content_json.as_deref()).unwrap_or_else(|| original.clone());
    for key in ["m.relates_to", "m.new_content", "m.mentions", FEED_POST_KEY] {
        base.remove(key);
    }
    let count = (f64::from(count_of(Some(&original))) + 1.0).min(FORWARD_COUNT_MAX);
    base.insert(FORWARD_KEY.to_owned(), json!({ "count": count as u32 }));
    serde_json::to_string(&Value::Object(base)).ok()
}

/// The forward line of any message: none for a count under 1.
#[uniffi::export]
pub fn forward_line(count: u32) -> Option<ForwardLine> {
    if count >= FORWARDED_MANY_TIMES {
        Some(ForwardLine::ForwardedManyTimes)
    } else {
        (count >= 1).then_some(ForwardLine::Forwarded)
    }
}

/// The most chats one forward goes to. `sent_counts`: the counts of the contents the forward sends.
#[uniffi::export]
pub fn forward_chat_limit(sent_counts: Vec<u32>) -> u32 {
    if sent_counts.iter().any(|&c| c >= FORWARDED_MANY_TIMES) { FORWARD_CHATS_MANY_TIMES } else { FORWARD_CHATS }
}

/// The source line of a voice message or an audio file.
#[uniffi::export]
pub fn voice_source(is_voice: bool, count: u32) -> VoiceSource {
    match forward_line(count) {
        Some(ForwardLine::ForwardedManyTimes) => VoiceSource::ForwardedManyTimes,
        Some(ForwardLine::Forwarded) => VoiceSource::Forwarded,
        None if is_voice => VoiceSource::Recorded,
        None => VoiceSource::File,
    }
}

fn count_of(content: Option<&Map<String, Value>>) -> u32 {
    let Some(n) = content.and_then(|c| c.get(FORWARD_KEY)).and_then(Value::as_object).and_then(|m| m.get("count")).and_then(Value::as_f64) else {
        return 0;
    };
    if n.is_finite() && n.fract() == 0.0 && n >= 1.0 { n.min(FORWARD_COUNT_MAX) as u32 } else { 0 }
}

// --- Cleared chats ---------------------------------------------------------------------------------------------------

/// The clear marker (ms) of a room in the chat.bipe.cleared_chats content, or `None`. A malformed event or entry counts
/// as not cleared.
#[uniffi::export]
pub fn cleared_chat_marker(content_json: Option<String>, room_id: String) -> Option<i64> {
    let content = object(content_json.as_deref())?;
    cleared_ts(rooms(&content)?.get(&room_id)?)
}

/// Every valid clear marker of the chat.bipe.cleared_chats content, by room ID (cleared_chat_marker for each room).
#[uniffi::export]
pub fn cleared_chat_markers(content_json: Option<String>) -> std::collections::HashMap<String, i64> {
    let Some(content) = object(content_json.as_deref()) else { return Default::default() };
    rooms(&content).map(|r| r.iter().filter_map(|(id, entry)| Some((id.clone(), cleared_ts(entry)?))).collect()).unwrap_or_default()
}

/// True when the chat list hides the room: it has a marker and no event after it.
#[uniffi::export]
pub fn cleared_chat_hidden(marker: Option<i64>, latest_ts: Option<i64>) -> bool {
    marker.is_some_and(|m| latest_ts.is_none_or(|t| t <= m))
}

/// True when the timeline and search keep an event with this origin_server_ts.
#[uniffi::export]
pub fn cleared_chat_keeps_event(marker: Option<i64>, event_ts: i64) -> bool {
    marker.is_none_or(|m| event_ts > m)
}

/// The content to write when the user clears `room_id` at `ts`. Keeps every other key and entry; the marker never
/// moves back.
#[uniffi::export]
pub fn cleared_chats_with_clear(content_json: Option<String>, room_id: String, ts: i64) -> String {
    let mut base = object(content_json.as_deref()).unwrap_or_default();
    let mut rooms = rooms(&base).cloned().unwrap_or_default();
    let old = rooms.get(&room_id).and_then(cleared_ts);
    let kept = match old {
        Some(o) if o > ts => o,
        _ => ts,
    };
    rooms.insert(room_id, json!({ "ts": kept }));
    base.insert("rooms".to_owned(), Value::Object(rooms));
    Value::Object(base).to_string()
}

fn rooms(content: &Map<String, Value>) -> Option<&Map<String, Value>> {
    content.get("rooms")?.as_object()
}

/// `ts` when it is a safe integer of 0 or more (JavaScript Number.isSafeInteger).
fn cleared_ts(entry: &Value) -> Option<i64> {
    let ts = entry.as_object()?.get("ts")?.as_f64()?;
    (ts.fract() == 0.0 && (0.0..=9_007_199_254_740_991.0).contains(&ts)).then_some(ts as i64)
}

// --- Assistant send ------------------------------------------------------------------------------------------------

/// Whether bipe-mcp may send: the switch content (account data chat.bipe.assistant_send) says enabled true.
#[uniffi::export]
pub fn assistant_send_allowed(content_json: Option<String>) -> bool {
    object(content_json.as_deref()).is_some_and(|c| c.get("enabled") == Some(&Value::Bool(true)))
}

/// The client name of a message an assistant sent (field chat.bipe.assistant), or `None`.
#[uniffi::export]
pub fn assistant_sender(message_content_json: Option<String>) -> Option<String> {
    let content = object(message_content_json.as_deref())?;
    let client = content.get(ASSISTANT_MARKER_FIELD)?.as_object()?.get("client").and_then(Value::as_str).map(js_trim).unwrap_or("");
    (!client.is_empty() && utf16_len(client) <= ASSISTANT_CLIENT_MAX_LENGTH).then(|| client.to_owned())
}

/// Members whose assistant reads the whole chat (ADR 0130): from a JSON array of state events, the state keys of the
/// reader events that their own sender set to true. Sorted, each once.
#[uniffi::export]
pub fn assistant_readers(state_events_json: String) -> Vec<String> {
    let Ok(Value::Array(events)) = serde_json::from_str::<Value>(&state_events_json) else { return Vec::new() };
    let mut out: Vec<String> = Vec::new();
    for e in events.iter().filter_map(Value::as_object) {
        let Some(key) = e.get("state_key").and_then(Value::as_str) else { continue };
        if e.get("type").and_then(Value::as_str) != Some(ASSISTANT_READER_EVENT_TYPE) || !key.starts_with('@') {
            continue;
        }
        if e.get("sender").and_then(Value::as_str) != Some(key) {
            continue;
        }
        let on = e.get("content").and_then(Value::as_object).and_then(|c| c.get(ASSISTANT_READER_FIELD)) == Some(&Value::Bool(true));
        if on && !out.iter().any(|o| o == key) {
            out.push(key.to_owned());
        }
    }
    // JavaScript sort(): UTF-16 code unit order.
    out.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
    out
}

// --- Drafts and work notes -------------------------------------------------------------------------------------------

/// Whether the apps show a drafts room event (JSON: type, sender, redacted, content) as a draft, and why not.
#[uniffi::export]
pub fn draft_from_event(event_json: String, own_user_id: String, joined_room_ids: Vec<String>) -> DraftResult {
    let hide = |reason: &str| DraftResult::Hide { reason: reason.to_owned() };
    let (c, f) = match own_text_message(&event_json, &own_user_id, "m.text", DRAFT_FIELD) {
        Ok(found) => found,
        Err(reason) => return hide(reason),
    };
    let Some(room_id) = f.get("room_id").and_then(Value::as_str).filter(|r| r.starts_with('!')) else { return hide("room_id") };
    if !joined_room_ids.iter().any(|r| r == room_id) {
        return hide("not_joined");
    }
    let Some(body) = c.get("body").and_then(Value::as_str).filter(|b| !js_trim(b).is_empty()) else { return hide("body") };
    if utf16_len(body) > DRAFT_BODY_MAX_LENGTH {
        return hide("body_too_long");
    }
    let Some(client) = f.get("client").and_then(Value::as_str).filter(|c| !js_trim(c).is_empty() && utf16_len(c) <= DRAFT_CLIENT_MAX_LENGTH)
    else {
        return hide("client");
    };
    let mut links = [None, None];
    for (i, key) in ["reply_to", "thread_root"].into_iter().enumerate() {
        // A key that is present must be an event ID, also when its value is null.
        if let Some(v) = f.get(key) {
            match v.as_str().filter(|s| s.starts_with('$')) {
                Some(id) => links[i] = Some(id.to_owned()),
                None => return hide(key),
            }
        }
    }
    let [reply_to, thread_root] = links;
    DraftResult::Show {
        draft: AssistantDraft { room_id: room_id.to_owned(), body: body.to_owned(), reply_to, thread_root, client: js_trim(client).to_owned() },
    }
}

/// Whether the apps show a notes room event as a note on a customer chat, and why not (ADR 0284 point 18).
#[uniffi::export]
pub fn work_note_from_event(event_json: String, own_user_id: String, joined_room_ids: Vec<String>, seat_ids: Vec<String>) -> WorkNoteResult {
    let hide = |reason: &str| WorkNoteResult::Hide { reason: reason.to_owned() };
    let (c, f) = match own_text_message(&event_json, &own_user_id, "m.text", WORK_NOTE_FIELD) {
        Ok(found) => found,
        Err(reason) => return hide(reason),
    };
    let Some(room_id) = f.get("room_id").and_then(Value::as_str).filter(|r| r.starts_with('!')) else { return hide("room_id") };
    if !joined_room_ids.iter().any(|r| r == room_id) {
        return hide("not_joined");
    }
    let Some(body) = c.get("body").and_then(Value::as_str).filter(|b| !js_trim(b).is_empty()) else { return hide("body") };
    if utf16_len(body) > WORK_NOTE_BODY_MAX_LENGTH {
        return hide("body_too_long");
    }
    let seat_id = f.get("seat_id").and_then(Value::as_str).filter(|s| seat_ids.iter().any(|x| x == s)).map(str::to_owned);
    WorkNoteResult::Show { note: WorkNote { room_id: room_id.to_owned(), body: body.to_owned(), seat_id } }
}

/// Who sent a reply to a customer, from a reply-by event of the notes room (ADR 0284 point 19).
#[uniffi::export]
pub fn reply_by_from_event(event_json: String, own_user_id: String, seat_ids: Vec<String>) -> ReplyByResult {
    let hide = |reason: &str| ReplyByResult::Hide { reason: reason.to_owned() };
    let (_, f) = match own_text_message(&event_json, &own_user_id, "m.notice", WORK_REPLY_BY_FIELD) {
        Ok(found) => found,
        Err(reason) => return hide(reason),
    };
    let Some(room_id) = f.get("room_id").and_then(Value::as_str).filter(|r| r.starts_with('!')) else { return hide("room_id") };
    let Some(event_id) = f.get("event_id").and_then(Value::as_str).filter(|e| e.starts_with('$')) else { return hide("event_id") };
    let Some(seat_id) = f.get("seat_id").and_then(Value::as_str).filter(|s| seat_ids.iter().any(|x| x == s)) else { return hide("seat_id") };
    ReplyByResult::Show { reply_by: ReplyBy { room_id: room_id.to_owned(), event_id: event_id.to_owned(), seat_id: seat_id.to_owned() } }
}

/// The checks every drafts and notes room event starts with, in reference order: type, redacted, sender, content
/// (msgtype), field. Returns the content and the field object.
fn own_text_message(event_json: &str, own_user_id: &str, msgtype: &str, field: &str) -> Result<(Map<String, Value>, Map<String, Value>), &'static str> {
    let event = object(Some(event_json)).ok_or("type")?;
    if event.get("type").and_then(Value::as_str) != Some("m.room.message") {
        return Err("type");
    }
    if event.get("redacted").is_some_and(truthy) {
        return Err("redacted");
    }
    if event.get("sender").and_then(Value::as_str) != Some(own_user_id) {
        return Err("sender");
    }
    let content = event.get("content").and_then(Value::as_object).filter(|c| c.get("msgtype").and_then(Value::as_str) == Some(msgtype)).ok_or("content")?;
    let f = content.get(field).and_then(Value::as_object).ok_or("field")?.clone();
    Ok((content.clone(), f))
}

/// JavaScript truthiness of a JSON value.
fn truthy(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().is_some_and(|f| f != 0.0 && !f.is_nan()),
        Value::String(s) => !s.is_empty(),
        Value::Array(_) | Value::Object(_) => true,
    }
}

fn object(json: Option<&str>) -> Option<Map<String, Value>> {
    match serde_json::from_str::<Value>(json?).ok()? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}
