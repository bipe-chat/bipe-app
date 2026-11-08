// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/badges.json against the crate. BIPE_SHARED sets the bipe-shared path.
//! The harnesses in core/harness run the same file through each packaged artifact.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use serde_json::Value;
use std::path::PathBuf;

const HOMESERVER: &str = "bipe.net.br";

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn trusted(file: &Value) -> Vec<TrustedKey> {
    file["publicKeys"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(kid, key)| TrustedKey { kid: kid.clone(), public_key: URL_SAFE_NO_PAD.decode(key.as_str().unwrap()).unwrap() })
        .collect()
}

#[test]
fn bound_context_passes_every_vector() {
    let file = shared("test-vectors/badges.json");
    let (keys, now) = (trusted(&file), file["now"].as_i64().unwrap());
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let result = verify_bound_sender_badge(
            vector["envelope"].to_string(),
            vector["sender"].as_str().unwrap().to_owned(),
            vector["senderMasterKey"].as_str().map(str::to_owned),
            keys.clone(),
            now,
        );
        assert_eq!(bound_badge_result_code(result), vector["expect"].as_str().unwrap(), "{}", vector["name"]);
    }
}

/// The public context has no sender and no master key, so those two failures are `valid` there.
#[test]
fn public_context_passes_every_vector() {
    let file = shared("test-vectors/badges.json");
    let (keys, now) = (trusted(&file), file["now"].as_i64().unwrap());
    for vector in file["vectors"].as_array().unwrap() {
        let expected = match vector["expect"].as_str().unwrap() {
            "subject_mismatch" | "master_key_mismatch" => "valid",
            other => other,
        };
        let result = verify_public_issuer_statement(vector["envelope"].to_string(), HOMESERVER.to_owned(), keys.clone(), now);
        assert_eq!(public_statement_result_code(result), expected, "{}", vector["name"]);
    }
}

#[test]
fn public_context_rejects_another_homeserver() {
    let file = shared("test-vectors/badges.json");
    let (keys, now) = (trusted(&file), file["now"].as_i64().unwrap());
    let result = verify_public_issuer_statement(file["vectors"][0]["envelope"].to_string(), "exemplo.nl".to_owned(), keys, now);
    assert_eq!(result, PublicStatementResult::Invalid { failure: BadgeFailure::SubjectMismatch });
}

#[test]
fn a_bad_trusted_key_matches_no_badge() {
    let file = shared("test-vectors/badges.json");
    let vector = &file["vectors"][0];
    let keys = vec![TrustedKey { kid: vector["envelope"]["kid"].as_str().unwrap().to_owned(), public_key: vec![0; 31] }];
    let result = verify_public_issuer_statement(vector["envelope"].to_string(), HOMESERVER.to_owned(), keys, file["now"].as_i64().unwrap());
    assert_eq!(result, PublicStatementResult::Invalid { failure: BadgeFailure::UnknownKid });
}

/// Fail closed: no cut or flipped byte of an envelope panics or verifies.
#[test]
fn damaged_envelopes_never_verify() {
    let file = shared("test-vectors/badges.json");
    let (keys, now) = (trusted(&file), file["now"].as_i64().unwrap());
    let vector = &file["vectors"][0];
    let json = vector["envelope"].to_string();
    let verify = |text: String| {
        verify_bound_sender_badge(
            text,
            vector["sender"].as_str().unwrap().to_owned(),
            vector["senderMasterKey"].as_str().map(str::to_owned),
            keys.clone(),
            now,
        )
    };
    assert!(matches!(verify(json.clone()), BoundBadgeResult::Valid { .. }));
    for end in 0..json.len() {
        assert!(matches!(verify(json[..end].to_owned()), BoundBadgeResult::Invalid { .. }), "cut at {end}");
    }
    let payload = vector["envelope"]["payload"].as_str().unwrap();
    let start = json.find(payload).unwrap();
    for index in start..start + payload.len() {
        let mut bytes = json.clone().into_bytes();
        bytes[index] = if bytes[index] == b'A' { b'B' } else { b'A' };
        let text = String::from_utf8(bytes).unwrap();
        // The last character can hold unused bits, so a change there can leave the same bytes.
        if index + 1 == start + payload.len() {
            continue;
        }
        assert!(matches!(verify(text), BoundBadgeResult::Invalid { .. }), "flip at {index}");
    }
}
