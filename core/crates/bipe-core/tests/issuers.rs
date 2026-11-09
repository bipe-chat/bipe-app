// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/badge-issuers.json against the crate (bipe-chat/bipe#637).
//! BIPE_SHARED sets the bipe-shared path.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

/// The device's issuer registry of the vectors: a registry payload with the vectors' issuers, schemes and regions.
fn registry(file: &Value) -> String {
    json!({ "issuers": file["trustedIssuers"], "schemes": file["schemes"], "regions": file["regions"] }).to_string()
}

fn verify(file: &Value, vector: &Value, registry: String) -> IssuedBadgeResult {
    verify_issued_badge(
        vector["envelope"].to_string(),
        vector["sender"].as_str().unwrap().to_owned(),
        vector["senderMasterKey"].as_str().map(str::to_owned),
        registry,
        file["lifeDays"].as_u64().unwrap() as u32,
        file["now"].as_i64().unwrap(),
    )
}

#[test]
fn every_issuer_vector_passes() {
    let file = shared("test-vectors/badge-issuers.json");
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let result = verify(&file, vector, registry(&file));
        assert_eq!(issued_badge_result_code(result), vector["expect"].as_str().unwrap(), "{}", vector["name"]);
    }
}

#[test]
fn a_valid_badge_names_its_issuer() {
    let file = shared("test-vectors/badge-issuers.json");
    let vector = file["vectors"].as_array().unwrap().iter().find(|v| v["name"] == "scheme: company in the Netherlands").unwrap();
    let IssuedBadgeResult::Valid { badge } = verify(&file, vector, registry(&file)) else { panic!("valid") };
    assert_eq!((badge.issuer_name.as_str(), badge.issuer_region.as_str()), ("Bipe Holanda Exemplo", "nl"));
    assert_eq!(badge.claims.level, BadgeLevel::Org);
    assert_eq!(badge.claims.iss, "bipe-nl-exemplo");
    assert_eq!(badge.claims.scheme.as_deref(), Some("nl.kvk"));
    assert_eq!(badge.claims.org_id.as_deref(), Some("12345678"));
    assert!(badge.claims.ext_json.is_some());
}

/// Fail closed: text that is not a registry trusts no issuer.
#[test]
fn no_registry_trusts_no_issuer() {
    let file = shared("test-vectors/badge-issuers.json");
    for text in ["", "null", "{}", "[]", "{\"issuers\":[]}"] {
        let result = verify(&file, &file["vectors"][0], text.to_owned());
        assert_eq!(result, IssuedBadgeResult::Invalid { failure: BadgeFailure::UnknownIssuer }, "{text:?}");
    }
}

/// A verifier from before ADR 0272 reads only badge-keys.json and knows no `person` or `org` level.
#[test]
fn flat_key_verifier_shows_no_scheme_seal() {
    let file = shared("test-vectors/badge-issuers.json");
    let keys: Vec<TrustedKey> = shared("test-vectors/badges.json")["publicKeys"]
        .as_object()
        .unwrap()
        .iter()
        .map(|(kid, key)| TrustedKey { kid: kid.clone(), public_key: URL_SAFE_NO_PAD.decode(key.as_str().unwrap()).unwrap() })
        .collect();
    for vector in file["verifierWithoutSchemes"].as_array().unwrap() {
        let result = verify_bound_sender_badge(
            vector["envelope"].to_string(),
            vector["sender"].as_str().unwrap().to_owned(),
            vector["senderMasterKey"].as_str().map(str::to_owned),
            keys.clone(),
            file["now"].as_i64().unwrap(),
        );
        assert_eq!(bound_badge_result_code(result), vector["expect"].as_str().unwrap(), "{}", vector["name"]);
    }
}

#[test]
fn every_class_case_passes() {
    let file = shared("test-vectors/badge-issuers.json");
    for case in file["classes"].as_array().unwrap() {
        let level = match case["lvl"].as_str().unwrap() {
            "pix" => BadgeLevel::Pix,
            "govbr" => BadgeLevel::Govbr,
            "cnpj" => BadgeLevel::Cnpj,
            "institution" => BadgeLevel::Institution,
            "brand-mark" => BadgeLevel::BrandMark,
            "person" => BadgeLevel::Person,
            "org" => BadgeLevel::Org,
            other => panic!("level {other}"),
        };
        let found = badge_class(level, case["scheme"].as_str().map(str::to_owned), registry(&file));
        let expected = (!case["expect"].is_null()).then(|| BadgeClass {
            class: case["expect"]["class"].as_str().unwrap().to_owned(),
            assurance: case["expect"]["assurance"].as_str().unwrap().to_owned(),
            country: case["expect"]["country"].as_str().unwrap().to_owned(),
            scheme: case["expect"]["scheme"].as_str().unwrap().to_owned(),
        });
        assert_eq!(found, expected, "{}", case["name"]);
    }
}

/// The committed version 1 registry is a registry this verifier reads: issuer `bipe` is trusted there.
#[test]
fn committed_registry_trusts_bipe() {
    let envelope = std::fs::read_to_string(
        std::env::var("BIPE_SHARED")
            .map(PathBuf::from)
            .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"))
            .join("signed/issuers/1.json"),
    )
    .unwrap();
    let payload: Value = serde_json::from_str(&envelope).unwrap();
    let bytes = URL_SAFE_NO_PAD.decode(payload["payload"].as_str().unwrap()).unwrap();
    let registry: Value = serde_json::from_slice(&bytes).unwrap();
    let bipe = registry["issuers"].as_array().unwrap().iter().find(|i| i["id"] == "bipe").unwrap();
    assert_eq!(bipe["status"], "valid");
    assert_eq!(badge_class(BadgeLevel::Govbr, None, String::from_utf8(bytes).unwrap()).unwrap().scheme, "br.govbr");
}
