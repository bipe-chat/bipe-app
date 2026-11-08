// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The single-issuer badge verifier (bipe-shared docs/badge-attestation.md, format v1).
//! Spike of bipe-chat/bipe#1172: no production client calls this code.
//! It covers no issuer registry, so a badge of level `person` or `org` is `malformed`
//! (the spec's case `verifierWithoutSchemes`).

use base64::Engine;
use base64::alphabet;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use ed25519_dalek::{Signature, VerifyingKey};
use serde_json::{Map, Value};

use crate::generated::registries::BRAND_MARK_LABELS;

/// Parser rules: the URL alphabet, no padding, and no rule on unused trailing bits.
const BASE64URL: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_allow_trailing_bits(true)
        .with_decode_padding_mode(DecodePaddingMode::RequireNone),
);

/// Parser rules: `iat` and `exp` range (31 December 9999, UTC).
const MAX_BADGE_TIME: i64 = 253_402_300_799;

/// One trusted Ed25519 public key.
#[derive(Clone, Debug, uniffi::Record)]
pub struct TrustedKey {
    pub kid: String,
    /// Raw 32 bytes. A key of another length matches no badge.
    pub public_key: Vec<u8>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BadgeLevel {
    Pix,
    Govbr,
    Institution,
    Cnpj,
    BrandMark,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BadgeCnpjMethod {
    Ecnpj,
    Partner,
    Staff,
}

/// The first failed check. `badge_failure_code` gives the code of the spec and the vectors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BadgeFailure {
    UnsupportedVersion,
    UnknownKid,
    InvalidSignature,
    Malformed,
    Expired,
    NotYetValid,
    SubjectMismatch,
    MasterKeyMismatch,
}

/// The signed fields. Every optional field is absent when the payload has no value for it.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BadgeClaims {
    pub sub: String,
    pub level: BadgeLevel,
    pub name: String,
    pub msk: String,
    pub iat: i64,
    pub exp: i64,
    pub cnpj: Option<String>,
    pub legal_name: Option<String>,
    pub trade_name: Option<String>,
    pub method: Option<BadgeCnpjMethod>,
    pub checked_month: Option<String>,
    pub mark: Option<String>,
    pub brand: Option<String>,
    pub label: Option<String>,
    /// `institution` only. Empty: no link in a notice is clickable.
    pub domains: Vec<String>,
    /// The `cvm` value as JSON text. A bad value changes nothing in the verdict, so the caller reads it leniently.
    pub cvm_json: Option<String>,
}

/// Context "Bound sender badge": the issuer attests to this sender, and the badge
/// binds to the sender's current cross-signing master key.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BoundSenderBadge {
    pub claims: BadgeClaims,
}

/// Context "Public issuer statement": the issuer signed this statement. It proves no
/// binding to a conversation sender or a master key. No conversion to `BoundSenderBadge` exists.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PublicIssuerStatement {
    pub claims: BadgeClaims,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BoundBadgeResult {
    Valid { badge: BoundSenderBadge },
    Invalid { failure: BadgeFailure },
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum PublicStatementResult {
    Valid { statement: PublicIssuerStatement },
    Invalid { failure: BadgeFailure },
}

/// Verifies a badge for a message sender. `sender_master_key` is the master key from the
/// Matrix SDK, absent when the app does not know it. `now_seconds` is Unix seconds.
#[uniffi::export]
pub fn verify_bound_sender_badge(
    envelope_json: String,
    sender: String,
    sender_master_key: Option<String>,
    trusted_keys: Vec<TrustedKey>,
    now_seconds: i64,
) -> BoundBadgeResult {
    let verdict = verified_claims(&envelope_json, &trusted_keys, now_seconds).and_then(|claims| {
        if claims.sub != sender {
            return Err(BadgeFailure::SubjectMismatch);
        }
        // No known master key fails closed, and `msk` is never empty here.
        if sender_master_key.as_deref() != Some(claims.msk.as_str()) {
            return Err(BadgeFailure::MasterKeyMismatch);
        }
        Ok(claims)
    });
    match verdict {
        Ok(claims) => BoundBadgeResult::Valid { badge: BoundSenderBadge { claims } },
        Err(failure) => BoundBadgeResult::Invalid { failure },
    }
}

/// Verifies a statement for a public page, which has no Matrix session. The subject rule of
/// the page: `sub` is an account on `homeserver`. Any other subject is `subject_mismatch`.
#[uniffi::export]
pub fn verify_public_issuer_statement(
    envelope_json: String,
    homeserver: String,
    trusted_keys: Vec<TrustedKey>,
    now_seconds: i64,
) -> PublicStatementResult {
    let verdict = verified_claims(&envelope_json, &trusted_keys, now_seconds).and_then(|claims| {
        if is_account_on(&claims.sub, &homeserver) { Ok(claims) } else { Err(BadgeFailure::SubjectMismatch) }
    });
    match verdict {
        Ok(claims) => PublicStatementResult::Valid { statement: PublicIssuerStatement { claims } },
        Err(failure) => PublicStatementResult::Invalid { failure },
    }
}

/// The code of a failure in the spec and in test-vectors/badges.json.
#[uniffi::export]
pub fn badge_failure_code(failure: BadgeFailure) -> String {
    match failure {
        BadgeFailure::UnsupportedVersion => "unsupported_version",
        BadgeFailure::UnknownKid => "unknown_kid",
        BadgeFailure::InvalidSignature => "invalid_signature",
        BadgeFailure::Malformed => "malformed",
        BadgeFailure::Expired => "expired",
        BadgeFailure::NotYetValid => "not_yet_valid",
        BadgeFailure::SubjectMismatch => "subject_mismatch",
        BadgeFailure::MasterKeyMismatch => "master_key_mismatch",
    }
    .to_owned()
}

/// `valid`, or the failure code. The vector harnesses use it on every client.
#[uniffi::export]
pub fn bound_badge_result_code(result: BoundBadgeResult) -> String {
    match result {
        BoundBadgeResult::Valid { .. } => "valid".to_owned(),
        BoundBadgeResult::Invalid { failure } => badge_failure_code(failure),
    }
}

/// `valid`, or the failure code. The vector harnesses use it on every client.
#[uniffi::export]
pub fn public_statement_result_code(result: PublicStatementResult) -> String {
    match result {
        PublicStatementResult::Valid { .. } => "valid".to_owned(),
        PublicStatementResult::Invalid { failure } => badge_failure_code(failure),
    }
}

/// Steps 1 to 5 and step 10 of "Verification order", which both contexts share.
fn verified_claims(envelope_json: &str, trusted_keys: &[TrustedKey], now: i64) -> Result<BadgeClaims, BadgeFailure> {
    use BadgeFailure::*;

    let envelope: Map<String, Value> = serde_json::from_str(envelope_json).map_err(|_| Malformed)?;
    // Check `v` before the other fields: a future version may have other fields.
    let version = envelope.get("v").and_then(Value::as_i64).ok_or(Malformed)?;
    if version != 1 {
        return Err(UnsupportedVersion);
    }
    let text = |key: &str| envelope.get(key).and_then(Value::as_str).ok_or(Malformed);
    let (kid, payload, sig) = (text("kid")?, text("payload")?, text("sig")?);

    let key = trusted_keys
        .iter()
        .find(|key| key.kid == kid)
        .and_then(|key| <[u8; 32]>::try_from(key.public_key.as_slice()).ok())
        .and_then(|key| VerifyingKey::from_bytes(&key).ok())
        .ok_or(UnknownKid)?;

    let payload_bytes = BASE64URL.decode(payload).map_err(|_| Malformed)?;
    let signature_bytes = BASE64URL.decode(sig).map_err(|_| Malformed)?;
    let signature = <[u8; 64]>::try_from(signature_bytes.as_slice()).map_err(|_| InvalidSignature)?;
    // The exact bytes, never a re-serialised payload. Strict verification accepts no more than the current providers.
    key.verify_strict(&payload_bytes, &Signature::from_bytes(&signature)).map_err(|_| InvalidSignature)?;

    let claims = parse_claims(&payload_bytes).ok_or(Malformed)?;
    if now >= claims.exp {
        return Err(Expired);
    }
    if now < claims.iat {
        return Err(NotYetValid);
    }
    Ok(claims)
}

/// Step 5. `None` is `malformed`. A known field of the wrong type is `malformed` on every level.
fn parse_claims(payload_bytes: &[u8]) -> Option<BadgeClaims> {
    let text = std::str::from_utf8(payload_bytes).ok()?;
    let payload: Map<String, Value> = serde_json::from_str(text).ok()?;

    let level = match payload.get("lvl")?.as_str()? {
        "pix" => BadgeLevel::Pix,
        "govbr" => BadgeLevel::Govbr,
        "institution" => BadgeLevel::Institution,
        "cnpj" => BadgeLevel::Cnpj,
        "brand-mark" => BadgeLevel::BrandMark,
        _ => return None,
    };
    let method = match optional_text(&payload, "method").ok()? {
        None => None,
        Some("ecnpj") => Some(BadgeCnpjMethod::Ecnpj),
        Some("partner") => Some(BadgeCnpjMethod::Partner),
        Some("staff") => Some(BadgeCnpjMethod::Staff),
        Some(_) => return None,
    };
    let owned = |key: &str| optional_text(&payload, key).map(|value| value.map(str::to_owned)).ok();

    let claims = BadgeClaims {
        sub: payload.get("sub")?.as_str()?.to_owned(),
        level,
        name: payload.get("name")?.as_str()?.to_owned(),
        msk: owned("msk")?.filter(|msk| !msk.is_empty())?,
        iat: badge_time(payload.get("iat")?)?,
        exp: badge_time(payload.get("exp")?)?,
        cnpj: owned("cnpj")?,
        legal_name: owned("legal_name")?,
        trade_name: owned("trade_name")?,
        method,
        checked_month: owned("checked_month")?,
        mark: owned("mark")?,
        brand: owned("brand")?,
        label: owned("label")?,
        domains: if level == BadgeLevel::Institution { domains(payload.get("domains"))? } else { Vec::new() },
        cvm_json: payload.get("cvm").filter(|cvm| !cvm.is_null()).map(Value::to_string),
    };

    let cnpj_ok = claims.cnpj.as_deref().is_some_and(is_cnpj);
    let level_ok = match level {
        BadgeLevel::Pix | BadgeLevel::Govbr => true,
        BadgeLevel::Institution => cnpj_ok,
        BadgeLevel::Cnpj => {
            cnpj_ok
                && claims.legal_name.as_deref().is_some_and(|name| !name.is_empty())
                && claims.method.is_some()
                && claims.checked_month.as_deref().is_some_and(is_month)
        }
        BadgeLevel::BrandMark => {
            cnpj_ok
                && !claims.name.is_empty()
                && claims.mark.as_deref().is_some_and(|mark| !mark.is_empty())
                && claims.brand.as_deref().is_some_and(|brand| !brand.is_empty())
                && claims.label.as_deref().is_some_and(|label| BRAND_MARK_LABELS.contains(&label))
        }
    };
    level_ok.then_some(claims)
}

/// A string, or nothing when the key is absent or null. Another type is an error.
fn optional_text<'a>(payload: &'a Map<String, Value>, key: &str) -> Result<Option<&'a str>, ()> {
    match payload.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(value)) => Ok(Some(value)),
        Some(_) => Err(()),
    }
}

/// Parser rules: a JSON integer in the range. A quoted number, a fraction and an exponent fail.
fn badge_time(value: &Value) -> Option<i64> {
    value.as_i64().filter(|time| (0..=MAX_BADGE_TIME).contains(time))
}

/// Absent or null is empty. Each item matches `^[a-z0-9-]+(\.[a-z0-9-]+)+$`.
fn domains(value: Option<&Value>) -> Option<Vec<String>> {
    match value {
        None | Some(Value::Null) => Some(Vec::new()),
        Some(Value::Array(items)) => {
            items.iter().map(|item| item.as_str().filter(|domain| is_domain(domain)).map(str::to_owned)).collect()
        }
        Some(_) => None,
    }
}

fn is_domain(domain: &str) -> bool {
    let mut labels = 0;
    for label in domain.split('.') {
        let ok = !label.is_empty() && label.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
        if !ok {
            return false;
        }
        labels += 1;
    }
    labels >= 2
}

/// `^[0-9A-Z]{12}[0-9]{2}$`
fn is_cnpj(cnpj: &str) -> bool {
    let bytes = cnpj.as_bytes();
    bytes.len() == 14
        && bytes[..12].iter().all(|b| b.is_ascii_digit() || b.is_ascii_uppercase())
        && bytes[12..].iter().all(u8::is_ascii_digit)
}

/// `^[0-9]{4}-(0[1-9]|1[0-2])$`
fn is_month(month: &str) -> bool {
    let bytes = month.as_bytes();
    bytes.len() == 7
        && bytes[..4].iter().all(u8::is_ascii_digit)
        && bytes[4] == b'-'
        && matches!((bytes[5], bytes[6]), (b'0', b'1'..=b'9') | (b'1', b'0'..=b'2'))
}

/// `sub` has the form `@local:server`, with no colon or white space in `local`.
fn is_account_on(sub: &str, homeserver: &str) -> bool {
    let mut parts = sub.split(':');
    let (Some(local), Some(server), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    let name = local.strip_prefix('@').unwrap_or("");
    // U+FEFF: the JavaScript \s of bipe-web src/badge.ts has it, and char::is_whitespace does not.
    server == homeserver && !name.is_empty() && !name.chars().any(|c| c.is_whitespace() || c == '\u{FEFF}')
}
