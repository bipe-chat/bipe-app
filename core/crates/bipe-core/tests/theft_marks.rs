// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/theft-marks.json against the crate. BIPE_SHARED sets the bipe-shared path.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use serde_json::Value;
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

#[test]
fn every_theft_mark_vector_passes() {
    let file = shared("test-vectors/theft-marks.json");
    let keys: Vec<TrustedKey> = file["publicKeys"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(kid, key)| TrustedKey { kid: kid.clone(), public_key: URL_SAFE_NO_PAD.decode(key.as_str().unwrap()).unwrap() })
        .collect();
    let now = file["now"].as_i64().unwrap();
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let name = vector["name"].as_str().unwrap();
        let result = verify_theft_mark(vector["envelope"].to_string(), vector["subject"].as_str().unwrap().to_owned(), keys.clone(), now);
        match (&result, vector["expected"]["error"].as_str()) {
            (TheftMarkResult::Valid { statement }, None) => {
                assert_eq!(statement.stolen_at, vector["expected"]["stolenAt"].as_i64().unwrap(), "{name}");
                assert_eq!(statement.sub, vector["subject"].as_str().unwrap(), "{name}");
            }
            (_, Some(code)) => assert_eq!(theft_mark_result_code(result.clone()), code, "{name}"),
            (_, None) => panic!("{name}: {result:?}"),
        }
    }
}

/// A badge never passes as a mark, and a mark never passes as a badge.
#[test]
fn a_mark_is_not_a_badge() {
    let file = shared("test-vectors/theft-marks.json");
    let keys: Vec<TrustedKey> = file["publicKeys"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(kid, key)| TrustedKey { kid: kid.clone(), public_key: URL_SAFE_NO_PAD.decode(key.as_str().unwrap()).unwrap() })
        .collect();
    let valid = file["vectors"].as_array().unwrap().iter().find(|v| v["name"] == "valid").unwrap();
    let as_badge = verify_bound_sender_badge(
        valid["envelope"].to_string(),
        valid["subject"].as_str().unwrap().to_owned(),
        Some("any".to_owned()),
        keys,
        file["now"].as_i64().unwrap(),
    );
    assert_eq!(bound_badge_result_code(as_badge), "malformed");
}
