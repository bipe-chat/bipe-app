// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Account and chat-list state rules: synced settings, DM selection, chat archive and Bipe user IDs. Reference:
//! bipe-shared tools/lib/registries.mjs. Vectors: synced-settings.json, dm-selection.json, chat-archive.json,
//! archive-sync.json.

use crate::signed_list::{BipeServerStatus, VerifiedList, bipe_server_status, user_id_server};
use serde_json::{Map, Value};
use std::collections::HashMap;

/// JavaScript Number.MAX_SAFE_INTEGER: the reference accepts safe integers only.
const MAX_SAFE_INTEGER: i64 = 9_007_199_254_740_991;

/// The account's settings after one device reads them (bipe-chat/bipe#1161). `server_json`: the event content, absent
/// or malformed counts as empty. `local_json`: the explicit values of this device. `pending`: keys changed here that
/// have not reached the server. `first`: this device has never read the event for this account. Every other key of
/// the server stays. Returns the content as JSON text.
#[uniffi::export]
pub fn synced_settings_reconcile(server_json: Option<String>, local_json: String, pending: Vec<String>, first: bool) -> String {
    let mut result = object(server_json.as_deref()).unwrap_or_default();
    let local = object(Some(&local_json)).unwrap_or_default();
    if first {
        for (key, value) in &local {
            result.entry(key.clone()).or_insert_with(|| value.clone());
        }
        if local.get("readReceipts") == Some(&Value::Bool(false)) {
            result.insert("readReceipts".to_owned(), Value::Bool(false));
        }
    }
    for key in &pending {
        if let Some(value) = local.get(key) {
            result.insert(key.clone(), value.clone());
        }
    }
    Value::Object(result).to_string()
}

/// One DM the chat list could show for a peer.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct DmCandidate {
    pub room_id: String,
    pub peer_user_id: String,
    pub eligible: bool,
    pub cleared: bool,
    pub latest_activity_ms: Option<i64>,
}

/// One eligible DM per peer, the most recent (on a tie, the smaller room ID), independent of the SDK's order. Peer
/// user ID to room ID. A cleared DM counts only with `including_cleared`.
#[uniffi::export]
pub fn newest_dm_rooms(candidates: Vec<DmCandidate>, including_cleared: bool) -> HashMap<String, String> {
    let mut chosen: HashMap<String, DmCandidate> = HashMap::new();
    for candidate in candidates {
        if !candidate.eligible || (!including_cleared && candidate.cleared) || candidate.room_id.is_empty() || candidate.peer_user_id.is_empty() {
            continue;
        }
        if let Some(previous) = chosen.get(&candidate.peer_user_id) {
            let time = candidate.latest_activity_ms.unwrap_or(i64::MIN);
            let old = previous.latest_activity_ms.unwrap_or(i64::MIN);
            // JavaScript string order compares UTF-16 code units.
            let not_smaller = candidate.room_id.encode_utf16().cmp(previous.room_id.encode_utf16()) != std::cmp::Ordering::Less;
            if time < old || (time == old && not_smaller) {
                continue;
            }
        }
        chosen.insert(candidate.peer_user_id.clone(), candidate);
    }
    chosen.into_iter().map(|(peer, room)| (peer, room.room_id)).collect()
}

/// Whether an archived chat stays archived: only a strictly newer incoming message brings it back. `marker` is the
/// archive marker (ms), `incoming` the newest incoming message (ms).
#[uniffi::export]
pub fn chat_archived(marker: Option<i64>, incoming: Option<i64>) -> bool {
    valid(marker).is_some_and(|m| !valid(incoming).is_some_and(|i| i > m))
}

/// The result of reconciling the Matrix archive tag with the local state.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ArchiveSync {
    pub marker: Option<i64>,
    pub synced: bool,
    /// `Some(true)`: set the tag; `Some(false)`: remove it; `None`: no write.
    pub tag_write: Option<bool>,
}

/// Reconciles the Matrix archive tag with a local incoming-message baseline and the one-time migration.
#[uniffi::export]
pub fn chat_archive_sync(marker: Option<i64>, synced: bool, server_archived: bool, activity: Option<i64>, incoming: Option<i64>) -> ArchiveSync {
    if let Some(m) = valid(marker) {
        if synced && !server_archived {
            return ArchiveSync { marker: None, synced: false, tag_write: None };
        }
        if !chat_archived(Some(m), incoming) {
            return ArchiveSync { marker: None, synced: false, tag_write: server_archived.then_some(false) };
        }
        return ArchiveSync { marker: Some(m), synced: server_archived, tag_write: (!server_archived).then_some(true) };
    }
    if server_archived {
        let since = valid(activity).unwrap_or(0).max(valid(incoming).unwrap_or(0));
        return ArchiveSync { marker: Some(since), synced: true, tag_write: None };
    }
    ArchiveSync { marker: None, synced: false, tag_write: None }
}

/// ADR 0168: a Bipe user is a user ID whose server name (after the first ':', lower case) is a valid operator of the
/// current signed operator list (bipe_server_status, stale rule included). The localpart must not be empty.
#[uniffi::export]
pub fn is_bipe_user_id(user_id: String, current: Option<VerifiedList>, now_seconds: i64) -> bool {
    let Some(server) = user_id_server(user_id) else { return false };
    server == server.to_lowercase() && bipe_server_status(current, server, now_seconds) == BipeServerStatus::Valid
}

/// A safe integer of 0 or more, as the reference checks with Number.isSafeInteger.
fn valid(value: Option<i64>) -> Option<i64> {
    value.filter(|v| (0..=MAX_SAFE_INTEGER).contains(v))
}

fn object(json: Option<&str>) -> Option<Map<String, Value>> {
    match serde_json::from_str::<Value>(json?).ok()? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}
