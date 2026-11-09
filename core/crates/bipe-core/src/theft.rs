// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The theft mark statement (bipe-shared docs/theft-mark.md, v1; ADR 0044, ADR 0051). The badge envelope and the badge
//! keys, with its own payload: `typ` `theft`, so a badge is never a mark and a mark is never a badge.
//! Vectors: bipe-shared test-vectors/theft-marks.json.

use serde_json::{Map, Value};

use crate::badge::{BadgeFailure, TrustedKey, badge_failure_code, badge_time, check_time_range, key_for, signed_payload};

/// A valid mark: the app shows the theft banner in chats with `sub`, and the marker on its messages sent from
/// `stolen_at - markedMessageMinutes` on.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TheftStatement {
    pub sub: String,
    /// When a guardian set the mark, Unix seconds.
    pub stolen_at: i64,
    pub iat: i64,
    pub exp: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TheftMarkResult {
    Valid { statement: TheftStatement },
    /// The codes are the badge codes (docs/theft-mark.md, verification order). Any error is no mark.
    Invalid { failure: BadgeFailure },
}

/// Verifies a theft statement for `subject`, the account the app asked about. `trusted_keys` are the badge keys.
#[uniffi::export]
pub fn verify_theft_mark(envelope_json: String, subject: String, trusted_keys: Vec<TrustedKey>, now_seconds: i64) -> TheftMarkResult {
    match verified(&envelope_json, &subject, &trusted_keys, now_seconds) {
        Ok(statement) => TheftMarkResult::Valid { statement },
        Err(failure) => TheftMarkResult::Invalid { failure },
    }
}

/// `valid`, or the failure code. The vector harnesses use it on every client.
#[uniffi::export]
pub fn theft_mark_result_code(result: TheftMarkResult) -> String {
    match result {
        TheftMarkResult::Valid { .. } => "valid".to_owned(),
        TheftMarkResult::Invalid { failure } => badge_failure_code(failure),
    }
}

fn verified(envelope_json: &str, subject: &str, keys: &[TrustedKey], now: i64) -> Result<TheftStatement, BadgeFailure> {
    // Steps 1 to 3.
    let bytes = signed_payload(envelope_json, |_, kid| key_for(keys, kid))?;
    // Step 4.
    let statement = parse(&bytes).ok_or(BadgeFailure::Malformed)?;
    // Step 5.
    check_time_range(statement.iat, statement.exp, now)?;
    // Step 6.
    if statement.sub != subject {
        return Err(BadgeFailure::SubjectMismatch);
    }
    Ok(statement)
}

/// Step 4. Integers only, in the time range of the badge parser rules.
fn parse(bytes: &[u8]) -> Option<TheftStatement> {
    let payload: Map<String, Value> = serde_json::from_str(std::str::from_utf8(bytes).ok()?).ok()?;
    if payload.get("typ")?.as_str()? != "theft" {
        return None;
    }
    Some(TheftStatement {
        sub: payload.get("sub")?.as_str()?.to_owned(),
        stolen_at: badge_time(payload.get("stolen_at")?)?,
        iat: badge_time(payload.get("iat")?)?,
        exp: badge_time(payload.get("exp")?)?,
    })
}
