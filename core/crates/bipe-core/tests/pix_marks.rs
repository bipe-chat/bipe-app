// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/pix-marks.json against the crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn text(value: &Value) -> String {
    value.as_str().unwrap().to_owned()
}

fn state(state: PixMarkState) -> &'static str {
    match state {
        PixMarkState::Issued => "issued",
        PixMarkState::Sent => "sent",
        PixMarkState::Received => "received",
        PixMarkState::NotReceived => "not_received",
    }
}

fn mark(mark: PixMark) -> &'static str {
    match mark {
        PixMark::Sent => "sent",
        PixMark::Received => "received",
        PixMark::NotReceived => "not_received",
    }
}

#[test]
fn every_lines_vector_passes() {
    let file = shared("test-vectors/pix-marks.json");
    let cases = file["lines"].as_array().unwrap();
    assert!(!cases.is_empty());
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let card = PixMarkCard {
            event_id: text(&case["card"]["event_id"]),
            requester: text(&case["card"]["requester"]),
            payers: case["card"]["payers"].as_array().unwrap().iter().map(text).collect(),
        };
        let events = case["events"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| PixMarkEvent {
                event_id: text(&e["event_id"]),
                sender: text(&e["sender"]),
                ts: e["ts"].as_i64().unwrap(),
                content_json: e.get("content").cloned().unwrap_or(Value::Null).to_string(),
            })
            .collect();
        let result = pix_mark_lines(card, events);
        let found = json!({
            "lines": result.lines.iter().map(|l| json!({
                "payer": l.payer,
                "state": state(l.state),
                "e2e_id": l.e2e_id,
                "paid": l.paid,
                "history": l.history.iter().map(|h| json!({ "event_id": h.event_id, "mark": mark(h.mark) })).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
            "rejected": result.rejected,
        });
        assert_eq!(found, case["expected"], "{name}");
    }
}

#[test]
fn every_txid_vector_passes() {
    let file = shared("test-vectors/pix-marks.json");
    for case in file["txid"].as_array().unwrap() {
        assert_eq!(pix_txid(text(&case["request_id"])), case["expected"].as_str().unwrap(), "{case}");
    }
}

/// The core checks the E2E ID by hand. When registries/pix-marks.json changes the pattern, this test fails.
#[test]
fn e2e_id_format_is_the_registry_pattern() {
    let registry = shared("registries/pix-marks.json");
    assert_eq!(registry["e2eIdPattern"], "^E[0-9]{8}[A-Za-z0-9]{23}$");
    assert_eq!(registry["eventType"], pix_mark_event_type());
    assert!(pix_e2e_id_valid("E12345678202610251200abcDEF12345".to_owned()));
    for id in ["", "E1234567820261025120abcDEF12345", "e12345678202610251200abcDEF12345", "E1234567A202610251200abcDEF12345", "E12345678202610251200abcDEF1234é"] {
        assert!(!pix_e2e_id_valid(id.to_owned()), "{id}");
    }
}

/// A content that the app sends is a mark that the lines accept.
#[test]
fn content_round_trip() {
    let card = PixMarkCard { event_id: "$card".into(), requester: "@loja:x".into(), payers: vec!["@ana:x".into()] };
    let sent = pix_mark_content("$card".into(), PixMark::Sent, None, Some("E12345678202610251200abcDEF12345".into())).unwrap();
    let received = pix_mark_content("$card".into(), PixMark::Received, Some("@ana:x".into()), None).unwrap();
    let events = vec![
        PixMarkEvent { event_id: "$1".into(), sender: "@ana:x".into(), ts: 1, content_json: sent },
        PixMarkEvent { event_id: "$2".into(), sender: "@loja:x".into(), ts: 2, content_json: received },
    ];
    let result = pix_mark_lines(card, events);
    assert!(result.rejected.is_empty());
    assert!(result.lines[0].paid);
    assert_eq!(result.lines[0].e2e_id.as_deref(), Some("E12345678202610251200abcDEF12345"));
    assert_eq!(pix_mark_content("$card".into(), PixMark::Received, None, Some("E12345678202610251200abcDEF12345".into())), None);
}
