// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Pure Bipe logic shared by all clients (ADR 0267). No network, files, clock, secrets or Matrix session.

mod badge;
mod chat_state;
mod cvm;
mod govbr;
mod identity;
mod issuer;
mod message_data;
mod notice;
mod person;
mod profile_facts;
mod pix;
mod pix_marks;
mod scam;
mod signed_list;
mod small_rules;
mod text;
mod theft;

/// bipe-shared tools/gen.mjs output (bipe-chat/bipe#1277). Never edit by hand.
mod generated {
    pub(crate) mod registries;
}

pub use badge::*;
pub use chat_state::*;
pub use cvm::*;
pub use govbr::*;
pub use identity::*;
pub use issuer::*;
pub use message_data::*;
pub use notice::*;
pub use person::*;
pub use profile_facts::*;
pub use pix::*;
pub use pix_marks::*;
pub use scam::*;
pub use signed_list::*;
pub use small_rules::*;
pub use text::{TextFold, normalize_text};
pub use theft::*;

uniffi::setup_scaffolding!("bipe_core");

// Link the ubrn wasm2 runtime: alloc, free and the panic hook.
#[cfg(target_family = "wasm")]
extern crate uniffi_runtime_wasm as _;
