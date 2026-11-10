// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/normalization.json, scam-detection.json and scam-warnings.json against the crate.
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

fn vectors(path: &str) -> Vec<Value> {
    shared(path)["vectors"].as_array().expect("vectors").clone()
}

fn kind_id(kind: ScamKind) -> &'static str {
    match kind {
        ScamKind::Extortion => "extortion",
        ScamKind::PixCode => "pixCode",
        ScamKind::Boleto => "boleto",
        ScamKind::RemoteApp => "remoteApp",
        ScamKind::PinRequest => "pinRequest",
        ScamKind::PixKey => "pixKey",
        ScamKind::DeliveryFee => "deliveryFee",
        ScamKind::LookalikeLink => "lookalikeLink",
        ScamKind::BankAlert => "bankAlert",
        ScamKind::Receipt => "receipt",
        ScamKind::NewNumber => "newNumber",
        ScamKind::Urgency => "urgency",
    }
}

#[test]
fn every_pattern_compiles() {
    let (compiled, total) = scam_patterns_compiled();
    assert_eq!(compiled, total);
}

#[test]
fn normalization() {
    for v in vectors("test-vectors/normalization.json") {
        let input = v["input"].as_str().unwrap().to_owned();
        assert_eq!(normalize_text(input.clone(), TextFold::Names), v["normalized"].as_str().unwrap(), "{input:?}");
    }
}

#[test]
fn javascript_whitespace() {
    // U+0085 is not JavaScript whitespace; U+FEFF and U+3000 are.
    assert_eq!(normalize_text("\u{FEFF} a\u{3000}\u{0085}b \u{2028}".into(), TextFold::Plain), "a \u{0085}b");
}

#[test]
fn scam_detection() {
    for v in vectors("test-vectors/scam-detection.json") {
        let message = v["message"].as_str().unwrap().to_owned();
        let expected: Vec<String> = v["matches"].as_array().unwrap().iter().map(|m| m.as_str().unwrap().to_owned()).collect();
        assert_eq!(scam_match_ids(message.clone()), expected, "{message:?}");
    }
}

#[test]
fn scam_warnings() {
    for v in vectors("test-vectors/scam-warnings.json") {
        let message = v["message"].as_str().unwrap().to_owned();
        let got = scam_warning_kind(message.clone(), vec![]).map(kind_id);
        assert_eq!(got, v["kind"].as_str(), "{message:?}");
    }
}

#[test]
fn skipping_passes_over_a_kind() {
    let message = "Faz um pix pra mim? É urgente. Chave 123.456.789-09".to_owned();
    let first = scam_warning_kind(message.clone(), vec![]).expect("a warning");
    assert_ne!(scam_warning_kind(message, vec![first]), Some(first));
}
