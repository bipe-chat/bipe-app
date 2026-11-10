// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared forward-marks.json, cleared-chats.json, assistant-send.json, drafts.json and work-notes.json against the
//! crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn list<'a>(file: &'a Value, key: &str) -> &'a Vec<Value> {
    let items = file[key].as_array().expect(key);
    assert!(!items.is_empty(), "{key}");
    items
}

/// JSON text of a vector value; JSON null is no value.
fn text(v: &Value) -> Option<String> {
    (!v.is_null()).then(|| v.to_string())
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_owned)).collect()).unwrap_or_default()
}

fn ts(v: &Value) -> Option<i64> {
    v.as_i64()
}

#[test]
fn forward_marks() {
    let f = shared("test-vectors/forward-marks.json");
    for v in list(&f, "count") {
        assert_eq!(forward_count(v["content"].to_string()), v["expected"].as_u64().unwrap() as u32, "{}", v["name"]);
    }
    for v in list(&f, "forward") {
        let got: Value = serde_json::from_str(&forwarded_content(v["content"].to_string(), text(&v["newContent"])).expect("content")).unwrap();
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
    for v in list(&f, "source") {
        let got = match voice_source(v["isVoice"].as_bool().unwrap(), v["count"].as_u64().unwrap() as u32) {
            VoiceSource::Forwarded => "forwarded",
            VoiceSource::ForwardedManyTimes => "forwardedManyTimes",
            VoiceSource::Recorded => "recorded",
            VoiceSource::File => "file",
        };
        assert_eq!(got, v["expected"], "{v}");
    }
    for v in list(&f, "line") {
        let got = forward_line(v["count"].as_u64().unwrap() as u32).map(|l| match l {
            ForwardLine::Forwarded => "forwarded",
            ForwardLine::ForwardedManyTimes => "forwardedManyTimes",
        });
        assert_eq!(got, v["expected"].as_str(), "{v}");
    }
    for v in list(&f, "chatLimit") {
        let counts: Vec<u32> = v["counts"].as_array().unwrap().iter().map(|c| c.as_u64().unwrap() as u32).collect();
        assert_eq!(forward_chat_limit(counts), v["expected"].as_u64().unwrap() as u32, "{}", v["name"]);
    }
}

#[test]
fn cleared_chats() {
    let f = shared("test-vectors/cleared-chats.json");
    for v in list(&f, "marker") {
        let room = v["room_id"].as_str().unwrap().to_owned();
        assert_eq!(cleared_chat_marker(text(&v["content"]), room.clone()), ts(&v["expected"]), "{}", v["name"]);
        assert_eq!(cleared_chat_markers(text(&v["content"])).get(&room).copied(), ts(&v["expected"]), "markers {}", v["name"]);
    }
    for v in list(&f, "hidden") {
        assert_eq!(cleared_chat_hidden(ts(&v["marker"]), ts(&v["latest_ts"])), v["expected"].as_bool().unwrap(), "{}", v["name"]);
    }
    for v in list(&f, "keeps") {
        assert_eq!(cleared_chat_keeps_event(ts(&v["marker"]), v["event_ts"].as_i64().unwrap()), v["expected"].as_bool().unwrap(), "{}", v["name"]);
    }
    for v in list(&f, "clear") {
        let out = cleared_chats_with_clear(text(&v["content"]), v["room_id"].as_str().unwrap().to_owned(), v["ts"].as_i64().unwrap());
        assert_eq!(serde_json::from_str::<Value>(&out).unwrap(), v["expected"], "{}", v["name"]);
    }
}

#[test]
fn assistant_send() {
    let f = shared("test-vectors/assistant-send.json");
    for v in list(&f, "switch") {
        assert_eq!(assistant_send_allowed(text(&v["content"])), v["expected"].as_bool().unwrap(), "{}", v["name"]);
    }
    for v in list(&f, "label") {
        assert_eq!(assistant_sender(text(&v["content"])).as_deref(), v["expected"].as_str(), "{}", v["name"]);
    }
    for v in list(&f, "readers") {
        assert_eq!(assistant_readers(v["events"].to_string()), strings(&v["expected"]), "{}", v["name"]);
    }
}

#[test]
fn drafts() {
    let f = shared("test-vectors/drafts.json");
    let own = f["context"]["ownUserId"].as_str().unwrap().to_owned();
    let joined = strings(&f["context"]["joinedRoomIds"]);
    for v in list(&f, "cases") {
        let got = match draft_from_event(v["event"].to_string(), own.clone(), joined.clone()) {
            DraftResult::Show { draft } => json!({ "show": true, "draft": {
                "roomId": draft.room_id, "body": draft.body, "replyTo": draft.reply_to, "threadRoot": draft.thread_root, "client": draft.client } }),
            DraftResult::Hide { reason } => json!({ "show": false, "reason": reason }),
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
}

#[test]
fn work_notes() {
    let f = shared("test-vectors/work-notes.json");
    let own = f["context"]["ownUserId"].as_str().unwrap().to_owned();
    let joined = strings(&f["context"]["joinedRoomIds"]);
    let seats = strings(&f["context"]["seatIds"]);
    for v in list(&f, "notes") {
        let got = match work_note_from_event(v["event"].to_string(), own.clone(), joined.clone(), seats.clone()) {
            WorkNoteResult::Show { note } => json!({ "show": true, "note": { "roomId": note.room_id, "body": note.body, "seatId": note.seat_id } }),
            WorkNoteResult::Hide { reason } => json!({ "show": false, "reason": reason }),
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
    for v in list(&f, "replyBy") {
        let got = match reply_by_from_event(v["event"].to_string(), own.clone(), seats.clone()) {
            ReplyByResult::Show { reply_by } => json!({ "show": true, "replyBy": {
                "roomId": reply_by.room_id, "eventId": reply_by.event_id, "seatId": reply_by.seat_id } }),
            ReplyByResult::Hide { reason } => json!({ "show": false, "reason": reason }),
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
}
