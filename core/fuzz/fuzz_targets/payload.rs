// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Any bytes as a signed payload, so the fuzzer reaches the claims parser behind the signature check. A valid result
//! must satisfy the spec's rules; the same input must give the same result twice.

#![no_main]

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use ed25519_dalek::{Signer, SigningKey};
use libfuzzer_sys::fuzz_target;

const NOW: i64 = 1_800_000_000;

fuzz_target!(|payload: &[u8]| {
    let signer = SigningKey::from_bytes(&[7; 32]);
    let envelope = serde_json::json!({
        "v": 1,
        "kid": "k1",
        "payload": URL_SAFE_NO_PAD.encode(payload),
        "sig": URL_SAFE_NO_PAD.encode(signer.sign(payload).to_bytes()),
    })
    .to_string();
    let keys = vec![TrustedKey { kid: "k1".to_owned(), public_key: signer.verifying_key().to_bytes().to_vec() }];
    // The same key as issuer `bipe` of a registry: every step of "Verification order" (bipe-chat/bipe#637).
    let registry = serde_json::json!({
        "issuers": [{ "id": "bipe", "name": "Bipe", "status": "valid", "region": "br",
            "levels": ["pix", "govbr", "institution", "cnpj", "brand-mark", "person", "org"], "schemes": ["br.pix", "xx.any"],
            "keys": { "k1": URL_SAFE_NO_PAD.encode(signer.verifying_key().to_bytes()) } }],
        "schemes": [{ "id": "br.pix", "country": "BR", "class": "person", "assurance": "bank", "alias": "pix" },
            { "id": "xx.any", "country": "XX", "class": "org", "assurance": "registry+control" }],
        "regions": [{ "id": "br", "servers": ["bipe.net.br"], "schemes": ["br.pix", "xx.any"] }],
    })
    .to_string();
    let issued = |sub: String, msk: Option<String>| verify_issued_badge(envelope.clone(), sub, msk, registry.clone(), 7, NOW);
    if let Ok(probe) = serde_json::from_slice::<serde_json::Value>(payload) {
        let sub = probe["sub"].as_str().unwrap_or("@a:bipe.net.br").to_owned();
        let msk = probe["msk"].as_str().map(str::to_owned);
        let first = issued(sub.clone(), msk.clone());
        assert_eq!(first, issued(sub, msk));
        if let IssuedBadgeResult::Valid { badge } = &first {
            assert_eq!((badge.claims.iss.as_str(), badge.issuer_region.as_str()), ("bipe", "br"));
            assert!(badge.claims.iat <= NOW && NOW < badge.claims.exp && !badge.claims.msk.is_empty());
            assert!(badge.claims.sub.ends_with(":bipe.net.br"));
            if matches!(badge.claims.level, BadgeLevel::Person | BadgeLevel::Org) {
                assert!(badge.claims.country.is_some() && badge.claims.assurance.is_some());
                assert!(badge.claims.scheme.as_deref().is_some_and(|s| s == "br.pix" || s == "xx.any"));
            }
        }
    }
    let statement = verify_public_issuer_statement(envelope.clone(), "bipe.net.br".to_owned(), keys.clone(), NOW);
    assert_eq!(statement, verify_public_issuer_statement(envelope.clone(), "bipe.net.br".to_owned(), keys.clone(), NOW));
    if let PublicStatementResult::Valid { statement } = &statement {
        let claims = &statement.claims;
        assert!(claims.iat <= NOW && NOW < claims.exp);
        assert!(!claims.msk.is_empty());
        assert!(claims.sub.ends_with(":bipe.net.br"));
        // The flat-key contexts know no `person` or `org` level (`verifierWithoutSchemes`).
        assert!(!matches!(claims.level, BadgeLevel::Person | BadgeLevel::Org));
        if claims.level != BadgeLevel::Institution {
            assert!(claims.domains.is_empty());
        }
        if matches!(claims.level, BadgeLevel::Cnpj | BadgeLevel::Institution | BadgeLevel::BrandMark) {
            assert!(claims.cnpj.as_deref().is_some_and(|cnpj| cnpj.len() == 14));
        }
        // The bound context with the right sender and master key accepts what the public context accepts.
        let bound = verify_bound_sender_badge(envelope, claims.sub.clone(), Some(claims.msk.clone()), keys, NOW);
        assert!(matches!(bound, BoundBadgeResult::Valid { .. }));
    }
});
