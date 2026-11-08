// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! core/harness/ed25519-cases.json through the public API (bipe-chat/bipe#1277 item 7). The payload of each case
//! is not JSON, so `malformed` means that the signature check passed, and any other failure means that it refused.
//! The Swift and JVM harnesses run the same cases against CryptoKit and Tink.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use serde_json::Value;

fn hex(text: &str) -> Vec<u8> {
    (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
}

/// `true` when bipe-core accepts the signature of the case.
fn core_accepts(case: &Value) -> bool {
    let text = |key: &str| hex(case[key].as_str().unwrap());
    let envelope = serde_json::json!({
        "v": 1,
        "kid": "case",
        "payload": URL_SAFE_NO_PAD.encode(text("message")),
        "sig": URL_SAFE_NO_PAD.encode(text("signature")),
    });
    let keys = vec![TrustedKey { kid: "case".to_owned(), public_key: text("publicKey") }];
    let result = verify_public_issuer_statement(envelope.to_string(), "bipe.net.br".to_owned(), keys, 1_800_000_000);
    match result {
        PublicStatementResult::Invalid { failure: BadgeFailure::Malformed } => true,
        PublicStatementResult::Invalid { failure: BadgeFailure::InvalidSignature | BadgeFailure::UnknownKid } => false,
        other => panic!("unexpected {other:?}"),
    }
}

/// Strict verification accepts case 3 only (speccheck results.md, "Dalek strict"). CryptoKit and Tink accept case 3
/// too, so the set is a subset of theirs; the harnesses check that on each platform.
#[test]
fn strict_verification_accepts_only_the_canonical_case() {
    let file: Value =
        serde_json::from_str(&std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../harness/ed25519-cases.json")).unwrap())
            .unwrap();
    let accepted: Vec<usize> =
        file["cases"].as_array().unwrap().iter().enumerate().filter(|(_, case)| core_accepts(case)).map(|(i, _)| i).collect();
    assert_eq!(accepted, vec![3]);
}
