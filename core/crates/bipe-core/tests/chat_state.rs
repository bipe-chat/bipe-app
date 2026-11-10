// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared synced-settings.json, dm-selection.json, chat-archive.json and archive-sync.json against the crate,
//! and is_bipe_user_id on the bundled-list fallback. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::collections::HashMap;
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

#[test]
fn synced_settings() {
    let f = shared("test-vectors/synced-settings.json");
    for c in list(&f, "cases") {
        let pending: Vec<String> = c["pending"].as_array().unwrap().iter().map(|k| k.as_str().unwrap().to_owned()).collect();
        let out = synced_settings_reconcile(Some(c["server"].to_string()), c["local"].to_string(), pending, c["first"].as_bool().unwrap());
        assert_eq!(serde_json::from_str::<Value>(&out).unwrap(), c["expected"], "{}", c["name"]);
    }
    assert_eq!(synced_settings_reconcile(Some("[1]".into()), "{}".into(), vec![], false), "{}");
}

#[test]
fn dm_selection() {
    let f = shared("test-vectors/dm-selection.json");
    for c in list(&f, "cases") {
        let candidates: Vec<DmCandidate> = c["candidates"]
            .as_array()
            .unwrap()
            .iter()
            .map(|x| DmCandidate {
                room_id: x["roomId"].as_str().unwrap_or("").to_owned(),
                peer_user_id: x["peerUserId"].as_str().unwrap_or("").to_owned(),
                eligible: x["eligible"].as_bool().unwrap_or(false),
                cleared: x["cleared"].as_bool().unwrap_or(false),
                latest_activity_ms: x["latestActivityMs"].as_i64(),
            })
            .collect();
        let as_json = |m: HashMap<String, String>| Value::Object(m.into_iter().map(|(k, v)| (k, json!(v))).collect());
        assert_eq!(as_json(newest_dm_rooms(candidates.clone(), false)), c["expected"], "{}", c["name"]);
        if !c["includingCleared"].is_null() {
            assert_eq!(as_json(newest_dm_rooms(candidates, true)), c["includingCleared"], "{} including cleared", c["name"]);
        }
    }
}

#[test]
fn chat_archive() {
    let f = shared("test-vectors/chat-archive.json");
    for v in list(&f, "vectors") {
        assert_eq!(chat_archived(v["marker"].as_i64(), v["incoming"].as_i64()), v["expected"].as_bool().unwrap(), "{}", v["name"]);
    }
    let f = shared("test-vectors/archive-sync.json");
    for c in list(&f, "cases") {
        let r = chat_archive_sync(c["marker"].as_i64(), c["synced"].as_bool().unwrap(), c["serverArchived"].as_bool().unwrap(), c["activity"].as_i64(), c["incoming"].as_i64());
        assert_eq!(json!({ "marker": r.marker, "synced": r.synced, "tagWrite": r.tag_write }), c["expected"], "{}", c["name"]);
    }
}

#[test]
fn bipe_user_ids_without_a_list() {
    // Before any valid operator list only the default server counts (bipe_server_status).
    assert!(is_bipe_user_id("@ana:bipe.net.br".into(), None, 0));
    assert!(!is_bipe_user_id("@ana:Bipe.net.br".into(), None, 0));
    assert!(!is_bipe_user_id("@:bipe.net.br".into(), None, 0));
    assert!(!is_bipe_user_id("ana:bipe.net.br".into(), None, 0));
    assert!(!is_bipe_user_id("@ana:matrix.org".into(), None, 0));
}
