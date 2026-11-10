// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared handles.json, handle-suggestions.json, invite-codes.json, phone-numbers.json, social-handles.json,
//! profile-checks.json, relationship-names.json and pix-check.json (field keys) against the crate.
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

fn list(path: &str, key: &str) -> Vec<Value> {
    let file = shared(path);
    let items = file[key].as_array().expect("list").clone();
    assert!(!items.is_empty(), "{path} {key}");
    items
}

fn text(v: &Value) -> String {
    v.as_str().expect("string").to_owned()
}

fn handle_id(p: HandleProblem) -> &'static str {
    match p {
        HandleProblem::TooShort => "too_short",
        HandleProblem::TooLong => "too_long",
        HandleProblem::InvalidCharacters => "invalid_characters",
        HandleProblem::Reserved => "reserved",
    }
}

#[test]
fn patterns_compile_and_the_handle_pattern_is_pinned() {
    assert!(identity_patterns_ok());
}

#[test]
fn handles() {
    for c in list("test-vectors/handles.json", "cases") {
        let input = text(&c["input"]);
        let claimant = c.get("claimant").map(text);
        assert_eq!(handle_problem(input.clone(), claimant.clone()).map(handle_id), c["problem"].as_str(), "{input:?} {claimant:?}");
        assert_eq!(handle_skeleton(input.clone()), text(&c["skeleton"]), "{input:?}");
    }
}

#[test]
fn handle_suggestion_vectors() {
    for c in list("test-vectors/handle-suggestions.json", "cases") {
        let name = text(&c["name"]);
        let expected: Vec<String> = c["suggestions"].as_array().unwrap().iter().map(text).collect();
        assert_eq!(handle_suggestions(name.clone()), expected, "{name:?}");
    }
}

#[test]
fn invite_codes() {
    for v in list("test-vectors/invite-codes.json", "vectors") {
        let input = text(&v["input"]);
        assert_eq!(extract_invite_code(input.clone()).as_deref(), v["code"].as_str(), "{input:?}");
    }
}

#[test]
fn phone_numbers() {
    for v in list("test-vectors/phone-numbers.json", "vectors") {
        let input = text(&v["input"]);
        assert_eq!(normalize_phone(input.clone()).as_deref(), v["e164"].as_str(), "{input:?}");
    }
    for v in list("test-vectors/phone-numbers.json", "display") {
        assert_eq!(display_phone(text(&v["e164"])), text(&v["display"]), "{}", v["e164"]);
    }
}

#[test]
fn social_handles() {
    for c in list("test-vectors/social-handles.json", "cases") {
        let (platform, input) = (text(&c["platform"]), text(&c["input"]));
        let own = c["ownHandle"].as_str().map(str::to_owned);
        assert_eq!(social_handle_clean(input.clone()), text(&c["handle"]), "{platform} {input:?}");
        let problem = social_handle_problem(platform.clone(), input.clone(), own.clone()).map(|p| match p {
            SocialHandleProblem::Invalid => "social_invalid",
            SocialHandleProblem::Reserved => "social_reserved",
        });
        assert_eq!(problem, c["problem"].as_str(), "{platform} {input:?} {own:?}");
        if let Some(url) = c["url"].as_str() {
            assert_eq!(social_url(platform.clone(), input.clone()).as_deref(), Some(url), "{platform} {input:?}");
        }
    }
}

#[test]
fn profile_checks() {
    for v in list("test-vectors/profile-checks.json", "profiles") {
        let problem = profile_problem(text(&v["name"]), text(&v["bio"])).map(|p| match p {
            ProfileProblem::NameEmpty => "name_empty",
            ProfileProblem::NameTooLong => "name_too_long",
            ProfileProblem::NameReserved => "name_reserved",
            ProfileProblem::BioTooLong => "bio_too_long",
            ProfileProblem::BioTooManyLines => "bio_too_many_lines",
        });
        assert_eq!(problem, v["problem"].as_str(), "{} {}", v["name"], v["bio"]);
    }
    for v in list("test-vectors/profile-checks.json", "seals") {
        assert_eq!(seal_lookalike(text(&v["name"])).as_deref(), v["seal"].as_str(), "{}", v["name"]);
    }
    for v in list("test-vectors/profile-checks.json", "collisions") {
        let contacts: Vec<String> = v["contacts"].as_array().unwrap().iter().map(text).collect();
        assert_eq!(contact_collision(text(&v["name"]), contacts).as_deref(), v["collision"].as_str(), "{}", v["name"]);
    }
}

#[test]
fn relationship_names() {
    for v in list("test-vectors/relationship-names.json", "vectors") {
        let warning = relationship_name_warning(
            v["savedName"].as_str().map(str::to_owned),
            v["verifiedName"].as_str().map(str::to_owned),
            v["nicknameConfirmed"].as_bool() == Some(true),
        )
        .map(|w| match w {
            RelationshipNameWarning::Mismatch => "mismatch",
            RelationshipNameWarning::Confirm => "confirm",
        });
        assert_eq!(warning, v["expected"].as_str(), "{}", v["name"]);
    }
}

#[test]
fn pix_keys_in_text() {
    for v in list("test-vectors/pix-check.json", "vectors") {
        let got: Vec<Value> = pix_keys(text(&v["text"])).into_iter().map(|k| json!({ "value": k.value, "types": k.types })).collect();
        assert_eq!(Value::Array(got), v["keys"], "{}", v["text"]);
    }
}
