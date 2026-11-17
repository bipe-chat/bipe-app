// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared onboarding-schedule.json, remote-banner.json, sticker-search.json and pins.json against the crate.
//! BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::Value;
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

fn s(v: &Value) -> String {
    v.as_str().expect("string").to_owned()
}

fn strings(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().map(s).collect()).unwrap_or_default()
}

#[test]
fn onboarding_schedule() {
    let f = shared("test-vectors/onboarding-schedule.json");
    for v in list(&f, "pinReminder") {
        let result = match v["result"].as_str().unwrap() {
            "right" => PinCheckResult::Right,
            "wrong" => PinCheckResult::Wrong,
            _ => PinCheckResult::Later,
        };
        let got = pin_reminder_next(v["step"].as_u64().unwrap() as u32, result);
        assert_eq!((got.step as u64, got.gap_days as u64), (v["nextStep"].as_u64().unwrap(), v["gapDays"].as_u64().unwrap()), "{v}");
    }
    for v in list(&f, "bringPeopleReminder") {
        let st = &v["state"];
        let state = BringPeopleState {
            declines: st["declines"].as_u64().unwrap() as u32,
            last_ask_at_ms: st["lastAskAt"].as_i64(),
            slots: st["slots"]
                .as_array()
                .unwrap()
                .iter()
                .map(|x| InviteSlot { status: s(&x["status"]), sent_at_ms: x["sentAt"].as_i64().unwrap() })
                .collect(),
        };
        let got = bring_people_reminder(state, v["nowMs"].as_i64().unwrap()).map(|r| {
            let kind = if r.kind == InviteSlotKind::Expired { "expired" } else { "pending" };
            serde_json::json!({ "slot": r.slot, "kind": kind })
        });
        assert_eq!(got.unwrap_or(Value::Null), v["expected"], "{}", v["name"]);
    }
    for v in list(&f, "chatListPrompt") {
        let slot = v["slot"].as_object().map(|o| PromptSlot { holder: s(&o["holder"]), since_ms: o["sinceMs"].as_i64().unwrap() });
        let got = chat_list_prompt(strings(&v["eligible"]), slot, v["nowMs"].as_i64().unwrap());
        assert_eq!(got.as_deref(), v["expected"].as_str(), "{}", v["name"]);
    }
}

#[test]
fn remote_banner() {
    let f = shared("test-vectors/remote-banner.json");
    for v in list(&f, "state") {
        let banner = v["banner"].as_object().map(|b| RemoteBanner {
            expires_at: b["expires_at"].as_str().unwrap_or("").to_owned(),
            min_app_version: b.get("min_app_version").and_then(Value::as_str).map(str::to_owned),
        });
        let got = match remote_banner_state(banner, s(&v["app_version"]), s(&v["now"])) {
            RemoteBannerState::Hidden => "hidden",
            RemoteBannerState::Update => "update",
            RemoteBannerState::Notice => "notice",
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
    for v in list(&f, "compare") {
        assert_eq!(i64::from(compare_app_versions(s(&v["a"]), s(&v["b"]))), v["expected"].as_i64().unwrap(), "{v}");
    }
}

#[test]
fn sticker_search() {
    let f = shared("test-vectors/sticker-search.json");
    for v in list(&f, "fold") {
        assert_eq!(sticker_search_fold(s(&v["input"])), s(&v["expected"]), "{v}");
    }
    for v in list(&f, "emoji_key") {
        assert_eq!(sticker_emoji_key(s(&v["input"])), s(&v["expected"]), "{v}");
    }
    for v in list(&f, "tags") {
        assert_eq!(sticker_query_tags(s(&v["query"])), strings(&v["expected"]), "{v}");
    }
    for v in list(&f, "matches") {
        let st = &v["sticker"];
        let got = sticker_matches(s(&v["query"]), st["body"].as_str().map(str::to_owned), strings(&st["tags"]), st["pack"].as_str().map(str::to_owned));
        assert_eq!(got, v["expected"].as_bool().unwrap(), "{}", v["name"]);
    }
}

#[test]
fn pins() {
    let f = shared("test-vectors/pins.json");
    for v in list(&f, "cases") {
        let got = pin_problem(s(&v["input"])).map(|p| match p {
            PinProblem::NotDigits => "not_digits",
            PinProblem::WrongLength => "wrong_length",
            PinProblem::RepeatedDigit => "repeated_digit",
            PinProblem::Sequence => "sequence",
            PinProblem::Date => "date",
        });
        assert_eq!(got, v["problem"].as_str(), "{}", v["input"]);
    }
}
