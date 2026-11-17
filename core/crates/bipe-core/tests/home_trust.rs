// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/home-score.json and trust-facts.json against the crate (bipe-chat/bipe#1478).
//! BIPE_SHARED sets the bipe-shared path.

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

fn chat(v: &Value) -> HomeChat {
    let flag = |k: &str| v[k].as_bool().unwrap_or(false);
    HomeChat {
        id: v["id"].as_str().unwrap().to_owned(),
        kind: match v["kind"].as_str().unwrap() {
            "person" => HomeChatKind::Person,
            "company" => HomeChatKind::Company,
            "group" => HomeChatKind::Group,
            other => panic!("kind {other}"),
        },
        has_seal: flag("hasSeal"),
        official: flag("official"),
        favourite: flag("favourite"),
        muted: flag("muted"),
        mentioned: flag("mentioned"),
        unread_count: v["unreadCount"].as_i64().unwrap_or(0),
        last_from_other: flag("lastFromOther"),
        minutes_since_activity: v["minutesSinceActivity"].as_i64(),
        messages_per_week: v["messagesPerWeek"].as_i64().unwrap_or(0),
        trust_warning: flag("trustWarning"),
        model_attention: v["modelAttention"].as_str().map(str::to_owned),
        model_due: flag("modelDue"),
    }
}

/// The score as the vectors write it: an integer JSON number when whole (0, 1), else a float.
fn score(percent: i32) -> Value {
    if percent % 100 == 0 { json!(percent / 100) } else { json!(f64::from(percent) / 100.0) }
}

fn reason(r: Option<HomeReason>) -> Value {
    match r {
        Some(HomeReason::Mention) => json!("mention"),
        Some(HomeReason::Unanswered) => json!("unanswered"),
        None => Value::Null,
    }
}

#[test]
fn home_score_vectors() {
    let f = shared("test-vectors/home-score.json");
    for c in list(&f, "score") {
        let tiles = home_plan(vec![chat(&c["input"])]);
        let tile = &tiles[0];
        let got = json!({ "score": score(tile.score_percent), "tier": tile.tier, "reason": reason(tile.reason) });
        assert_eq!(got, c["expected"], "{}", c["name"]);
        let alone = home_score(chat(&c["input"]));
        assert_eq!((alone.score_percent, alone.reason), (tile.score_percent, tile.reason), "{}", c["name"]);
    }
    for c in list(&f, "plan") {
        let chats = c["input"].as_array().unwrap().iter().map(chat).collect();
        let got: Vec<Value> = home_plan(chats)
            .into_iter()
            .map(|t| json!({ "id": t.id, "score": score(t.score_percent), "tier": t.tier }))
            .collect();
        assert_eq!(Value::Array(got), c["expected"], "{}", c["name"]);
    }
}

#[test]
fn trust_facts_vectors() {
    let f = shared("test-vectors/trust-facts.json");
    for c in list(&f, "vectors") {
        let i = &c["input"];
        let s = |k: &str| i[k].as_str().map(str::to_owned);
        let t = |k: &str| i[k].as_f64();
        let input = TrustInput {
            account_id: s("accountId"),
            device_key: s("deviceKey"),
            lifecycle_known: i["lifecycleKnown"].as_bool(),
            device_changed_at: t("deviceChangedAt"),
            identity_changed_at: t("identityChangedAt"),
            badge_status: s("badgeStatus"),
            badge_account_id: s("badgeAccountId"),
            badge_device_key: s("badgeDeviceKey"),
            badge_name: s("badgeName"),
            badge_issued_at: t("badgeIssuedAt"),
            confirmation_method: s("confirmationMethod"),
            confirmation_account_id: s("confirmationAccountId"),
            confirmation_device_key: s("confirmationDeviceKey"),
            confirmed_at: t("confirmedAt"),
            prior_in_person_confirmed_at: t("priorInPersonConfirmedAt"),
            warning_kind: s("warningKind"),
        };
        let out = trust_facts(input);
        let got = json!({
            "identity": out.identity, "verifiedName": out.verified_name, "contact": out.contact,
            "change": out.change, "warningKind": out.warning_kind,
        });
        assert_eq!(got, c["expected"], "{}", c["name"]);
    }
}
