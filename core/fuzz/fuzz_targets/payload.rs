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
    let statement = verify_public_issuer_statement(envelope.clone(), "bipe.net.br".to_owned(), keys.clone(), NOW);
    assert_eq!(statement, verify_public_issuer_statement(envelope.clone(), "bipe.net.br".to_owned(), keys.clone(), NOW));
    if let PublicStatementResult::Valid { statement } = &statement {
        let claims = &statement.claims;
        assert!(claims.iat <= NOW && NOW < claims.exp);
        assert!(!claims.msk.is_empty());
        assert!(claims.sub.ends_with(":bipe.net.br"));
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
