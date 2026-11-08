// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Any text as the envelope. Both contexts return a result and never panic. Nothing the fuzzer makes can carry a
//! signature of the trusted key, so no input is valid.

#![no_main]

use bipe_core::*;
use ed25519_dalek::SigningKey;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(text) = std::str::from_utf8(data) else { return };
    let key = TrustedKey { kid: "k1".to_owned(), public_key: SigningKey::from_bytes(&[7; 32]).verifying_key().to_bytes().to_vec() };
    let bound = verify_bound_sender_badge(text.to_owned(), "@a:bipe.net.br".to_owned(), Some("m".to_owned()), vec![key.clone()], 1_800_000_000);
    assert!(matches!(bound, BoundBadgeResult::Invalid { .. }));
    let statement = verify_public_issuer_statement(text.to_owned(), "bipe.net.br".to_owned(), vec![key], 1_800_000_000);
    assert!(matches!(statement, PublicStatementResult::Invalid { .. }));
});
