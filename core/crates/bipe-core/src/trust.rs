// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Trust facts of one contact (ADR 0074, bipe-chat/bipe#1478). The rules are compiled from
//! bipe-shared registries/trust-facts.json into generated/registries.rs. Vectors: test-vectors/trust-facts.json.

pub use crate::generated::registries::{TrustFacts, TrustInput};

/// Identity proof, personal confirmation, lifecycle change and the selected warning of one contact. The inputs come
/// from authenticated adapters; the warning kind is kept as given.
#[uniffi::export]
pub fn trust_facts(input: TrustInput) -> TrustFacts {
    crate::generated::registries::trust_rules(&input)
}
