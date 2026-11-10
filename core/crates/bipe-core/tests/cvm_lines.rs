// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/cvm-lines.json against the crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn key(key: CvmLineKey) -> &'static str {
    match key {
        CvmLineKey::Registered => "bipe_cvm_registered",
        CvmLineKey::Cancelled => "bipe_cvm_cancelled",
        CvmLineKey::NoRegistration => "bipe_cvm_no_registration",
        CvmLineKey::BankNote => "bipe_cvm_bank_note",
        CvmLineKey::StopOrder => "bipe_cvm_stop_order",
    }
}

#[test]
fn every_cvm_vector_passes() {
    let file = shared("test-vectors/cvm-lines.json");
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let result = cvm_lines(vector.get("cvm").map(Value::to_string));
        let lines: Vec<Value> = result
            .lines
            .iter()
            .map(|l| match &l.arg {
                Some(arg) => json!({ "key": key(l.key), "arg": arg }),
                None => json!({ "key": key(l.key) }),
            })
            .collect();
        assert_eq!(Value::Array(lines), vector["lines"], "{}", vector["name"]);
        assert_eq!(result.danger, vector["danger"].as_bool().unwrap(), "{}", vector["name"]);
    }
}

#[test]
fn not_an_object_shows_nothing() {
    for text in ["", "null", "[]", "\"registered\"", "{\"st\":1}", "{\"st\":\"registered\",\"cat\":\"corretora\"}"] {
        assert!(cvm_lines(Some(text.to_owned())).lines.is_empty(), "{text}");
    }
    assert!(cvm_lines(None).lines.is_empty());
}

/// The length limit counts UTF-16 units and the untrimmed text, as the reference.
#[test]
fn category_limits() {
    let at = |c: String| cvm_lines(Some(json!({ "st": "registered", "cat": [c] }).to_string())).lines.len();
    assert_eq!(at("a".repeat(120)), 1);
    assert_eq!(at("a".repeat(121)), 0);
    assert_eq!(at(format!(" {} ", "a".repeat(119))), 0);
    assert_eq!(at("😀".repeat(60)), 1);
    assert_eq!(at("😀".repeat(61)), 0);
    assert_eq!(at("\u{feff}".to_owned()), 0);
    let eleven: Vec<String> = (0..11).map(|i| format!("c{i}")).collect();
    assert!(cvm_lines(Some(json!({ "st": "registered", "cat": eleven }).to_string())).lines.is_empty());
}
