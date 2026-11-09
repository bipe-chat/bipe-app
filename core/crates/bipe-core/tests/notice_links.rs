// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/notice-links.json against the crate. BIPE_SHARED sets the bipe-shared path.

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
fn every_notice_link_vector_passes() {
    let file = shared("test-vectors/notice-links.json");
    let domains: Vec<String> = serde_json::from_value(file["domains"].clone()).unwrap();
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let url = vector["url"].as_str().unwrap().to_owned();
        assert_eq!(notice_link_clickable(url, domains.clone()), vector["clickable"].as_bool().unwrap(), "{}", vector["name"]);
    }
}

#[test]
fn domains() {
    for (domain, valid) in [
        ("exemplo.sp.gov.br", true),
        ("bipe.chat", true),
        ("a-b.c0", true),
        ("bipe", false),
        ("Bipe.chat", false),
        ("bipe..chat", false),
        (".bipe.chat", false),
        ("bipe.chat.", false),
        ("bipé.chat", false),
        ("", false),
    ] {
        assert_eq!(notice_domain_valid(domain.to_owned()), valid, "{domain}");
    }
}

/// A domain that is not valid makes no link clickable, even an exact match.
#[test]
fn invalid_domains_trust_nothing() {
    assert!(!notice_link_clickable("https://bipe/x".to_owned(), vec!["bipe".to_owned()]));
    assert!(!notice_link_clickable("https://bipe.chat/".to_owned(), vec!["Bipe.chat".to_owned()]));
    assert!(notice_link_clickable("https://bipe.chat./x".to_owned(), vec!["bipe.chat".to_owned()]));
    assert!(!notice_link_clickable("https://evilbipe.chat/".to_owned(), vec!["bipe.chat".to_owned()]));
}
