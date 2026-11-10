// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/profile-facts.json against the crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Map, Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn times(facts: &Value, key: &str) -> Vec<String> {
    facts[key].as_array().map(|a| a.iter().filter_map(|t| t.as_str().map(str::to_owned)).collect()).unwrap_or_default()
}

/// The reference object: count only when present, numberNotice only when present.
fn as_json(out: &ProfileFactLines) -> Value {
    let lines: Vec<Value> = out
        .lines
        .iter()
        .map(|l| {
            let mut o = Map::new();
            let fact = match l.fact {
                ProfileFact::Created => "created",
                ProfileFact::Photo => "photo",
                ProfileFact::Name => "name",
                ProfileFact::NumberOut => "numberOut",
                ProfileFact::NumberIn => "numberIn",
            };
            o.insert("fact".into(), json!(fact));
            if let Some(c) = l.count {
                o.insert("count".into(), json!(c));
            }
            o.insert("at".into(), json!(iso_time(l.at_ms)));
            Value::Object(o)
        })
        .collect();
    let mut o = Map::new();
    o.insert("lines".into(), Value::Array(lines));
    o.insert("notice".into(), json!(out.notice));
    if let Some(n) = out.number_notice {
        o.insert("numberNotice".into(), json!(if n == NumberNotice::In { "in" } else { "out" }));
    }
    Value::Object(o)
}

#[test]
fn profile_fact_vectors() {
    let file = shared("test-vectors/profile-facts.json");
    let now = parse_iso_time(file["now"].as_str().unwrap()).expect("now");
    for (key, always_created) in [("vectors", false), ("profile_vectors", true)] {
        let vectors = file[key].as_array().expect(key);
        assert!(!vectors.is_empty(), "{key}");
        for v in vectors {
            let f = &v["facts"];
            let facts = ProfileFacts {
                created_at: f["created_at"].as_str().map(str::to_owned),
                name_changes: times(f, "name_changes"),
                photo_changes: times(f, "photo_changes"),
                number_moved_out: times(f, "number_moved_out"),
                number_moved_in: times(f, "number_moved_in"),
            };
            assert_eq!(as_json(&profile_fact_lines(facts, now, always_created)), v["expected"], "{key} {}", v["name"]);
        }
    }
}

#[test]
fn iso_times() {
    for t in ["1970-01-01T00:00:00.000Z", "2024-02-29T23:59:59.999Z", "2026-10-25T12:00:00.000Z", "2000-03-01T00:00:00.000Z"] {
        assert_eq!(iso_time(parse_iso_time(t).unwrap()), t);
    }
    assert_eq!(parse_iso_time("2026-10-25"), parse_iso_time("2026-10-25T00:00:00Z"));
    assert_eq!(parse_iso_time("2026-10-25T12:00+02:00"), parse_iso_time("2026-10-25T10:00:00.000Z"));
    assert_eq!(parse_iso_time("2026-10-25T12:00:00.1234Z"), parse_iso_time("2026-10-25T12:00:00.123Z"));
    for bad in ["not a date", "2026-02-30", "2026-10-25T12:00:00", "2026-13-01", "2026-10-25T25:00Z", ""] {
        assert_eq!(parse_iso_time(bad), None, "{bad}");
    }
}
