// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/govbr-verify.json against the crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn phase(id: &str) -> GovbrPhase {
    match id {
        "ready" => GovbrPhase::Ready,
        "preparing" => GovbrPhase::Preparing,
        "waitingForSignature" => GovbrPhase::WaitingForSignature,
        "checking" => GovbrPhase::Checking,
        "verified" => GovbrPhase::Verified,
        "rejected" => GovbrPhase::Rejected,
        _ => GovbrPhase::Failed,
    }
}

fn phase_id(p: GovbrPhase) -> &'static str {
    match p {
        GovbrPhase::Ready => "ready",
        GovbrPhase::Preparing => "preparing",
        GovbrPhase::WaitingForSignature => "waitingForSignature",
        GovbrPhase::Checking => "checking",
        GovbrPhase::Verified => "verified",
        GovbrPhase::Rejected => "rejected",
        GovbrPhase::Failed => "failed",
    }
}

fn reason_id(r: GovbrReason) -> &'static str {
    match r {
        GovbrReason::NotSigned => "not_signed",
        GovbrReason::NotGovbr => "not_govbr",
        GovbrReason::WrongDocument => "wrong_document",
        GovbrReason::Expired => "expired",
        GovbrReason::CpfInUse => "cpf_in_use",
        GovbrReason::ChangedAfterSigning => "changed_after_signing",
    }
}

#[test]
fn govbr_vectors() {
    let f = shared("test-vectors/govbr-verify.json");
    let max = f["maxDocumentBytes"].as_i64().unwrap();
    for v in f["incoming"].as_array().unwrap() {
        let got = match govbr_incoming(phase(v["phase"].as_str().unwrap()), v["byteCount"].as_i64().unwrap(), max) {
            GovbrIncoming::Upload => "upload",
            GovbrIncoming::NoOpenVerification => "noOpenVerification",
            GovbrIncoming::Ignore => "ignore",
            GovbrIncoming::NotSigned => "notSigned",
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
    for v in f["results"].as_array().unwrap() {
        let r = govbr_result(v["state"].as_str().unwrap().to_owned(), v["reason"].as_str().map(str::to_owned));
        let got = match r.reason {
            Some(reason) => json!({ "phase": phase_id(r.phase), "reason": reason_id(reason) }),
            None => json!({ "phase": phase_id(r.phase) }),
        };
        assert_eq!(got, v["expected"], "{}", v["name"]);
    }
}
