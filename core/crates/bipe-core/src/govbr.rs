// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The pure parts of "Verificar com gov.br" (bipe-chat/bipe#544, spec product/screens/govbr-verify.json). Reference:
//! bipe-shared tools/lib/govbr.mjs. Vectors: test-vectors/govbr-verify.json. The network steps and the flow stay in
//! the apps.

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GovbrPhase {
    Ready,
    Preparing,
    WaitingForSignature,
    Checking,
    Verified,
    Rejected,
    Failed,
}

/// Why gov.br verification refused the document.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GovbrReason {
    NotSigned,
    NotGovbr,
    WrongDocument,
    Expired,
    CpfInUse,
    ChangedAfterSigning,
}

/// What the app does with a PDF that arrives (share sheet, open with, or the "Já assinei" picker).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum GovbrIncoming {
    /// Send it with PUT .../document.
    Upload,
    /// Show bipe_govbr_no_open_verification and do not upload.
    NoOpenVerification,
    /// A check runs; drop the file without a message.
    Ignore,
    /// Empty or bigger than the limit: show the reason not_signed without an upload.
    NotSigned,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GovbrResult {
    pub phase: GovbrPhase,
    pub reason: Option<GovbrReason>,
}

/// What to do with an incoming PDF of `byte_count` bytes in `phase`.
#[uniffi::export]
pub fn govbr_incoming(phase: GovbrPhase, byte_count: i64, max_document_bytes: i64) -> GovbrIncoming {
    match phase {
        GovbrPhase::Checking => GovbrIncoming::Ignore,
        GovbrPhase::WaitingForSignature if byte_count <= 0 || byte_count > max_document_bytes => GovbrIncoming::NotSigned,
        GovbrPhase::WaitingForSignature => GovbrIncoming::Upload,
        _ => GovbrIncoming::NoOpenVerification,
    }
}

/// The phase after a VerificationStatus answer of PUT .../document. A reason the app does not know fails closed.
#[uniffi::export]
pub fn govbr_result(state: String, reason: Option<String>) -> GovbrResult {
    let failed = GovbrResult { phase: GovbrPhase::Failed, reason: None };
    let rejected = |reason| GovbrResult { phase: GovbrPhase::Rejected, reason: Some(reason) };
    match state.as_str() {
        "verified" => GovbrResult { phase: GovbrPhase::Verified, reason: None },
        "expired" => rejected(GovbrReason::Expired),
        "rejected" => match reason.as_deref() {
            None => rejected(GovbrReason::Expired),
            Some(r) => govbr_reason(r).map_or(failed, rejected),
        },
        _ => failed,
    }
}

fn govbr_reason(id: &str) -> Option<GovbrReason> {
    Some(match id {
        "not_signed" => GovbrReason::NotSigned,
        "not_govbr" => GovbrReason::NotGovbr,
        "wrong_document" => GovbrReason::WrongDocument,
        "expired" => GovbrReason::Expired,
        "cpf_in_use" => GovbrReason::CpfInUse,
        "changed_after_signing" => GovbrReason::ChangedAfterSigning,
        _ => return None,
    })
}
