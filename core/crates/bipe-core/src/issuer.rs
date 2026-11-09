// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Badges from several issuers (ADR 0170, ADR 0272, bipe-chat/bipe#637): every step of
//! "Verification order" in bipe-shared docs/badge-attestation.md, with the issuer registry of
//! docs/signed-lists.md as the trusted issuers. Vectors: bipe-shared test-vectors/badge-issuers.json.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use ed25519_dalek::VerifyingKey;
use serde_json::{Map, Value};

use crate::badge::{BIPE_ISSUER, BadgeClaims, BadgeFailure, BadgeLevel, badge_failure_code, check_time, parse_claims, signed_payload};
use crate::signed_list::user_id_server;

/// A badge that passed every step, with the issuer the app names in the profile and the seal sheet.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct IssuedBadge {
    pub claims: BadgeClaims,
    /// The issuer `name` from the registry.
    pub issuer_name: String,
    /// The region of the issuer, for example `br`.
    pub issuer_region: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum IssuedBadgeResult {
    Valid { badge: IssuedBadge },
    Invalid { failure: BadgeFailure },
}

/// What a level proves for a feature gate (`badgeClass` of the reference). `institution` and
/// `brand-mark` have no class.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct BadgeClass {
    /// `person` or `org`.
    pub class: String,
    pub assurance: String,
    pub country: String,
    pub scheme: String,
}

/// Verifies a badge for a message sender against the issuer registry.
///
/// `issuer_registry_json` is the payload JSON of the device's current valid issuer registry
/// (`VerifiedList.payload_json`; every app build carries a bundled one). Text that is not a registry
/// trusts no issuer, so every badge is `unknown_issuer` (fail closed). `life_days` is `lifeDays` of
/// bipe-shared registries/badge-keys.json. `sender_master_key` is absent when the app does not know it.
#[uniffi::export]
pub fn verify_issued_badge(
    envelope_json: String,
    sender: String,
    sender_master_key: Option<String>,
    issuer_registry_json: String,
    life_days: u32,
    now_seconds: i64,
) -> IssuedBadgeResult {
    let registry = Registry::parse(&issuer_registry_json);
    match verified(&envelope_json, &sender, sender_master_key.as_deref(), registry.as_ref(), life_days, now_seconds) {
        Ok(badge) => IssuedBadgeResult::Valid { badge },
        Err(failure) => IssuedBadgeResult::Invalid { failure },
    }
}

/// `valid`, or the failure code. The vector harnesses use it on every client.
#[uniffi::export]
pub fn issued_badge_result_code(result: IssuedBadgeResult) -> String {
    match result {
        IssuedBadgeResult::Valid { .. } => "valid".to_owned(),
        IssuedBadgeResult::Invalid { failure } => badge_failure_code(failure),
    }
}

/// The class of a verified badge. `scheme` counts only for `person` and `org`; the Brazilian levels
/// `pix`, `govbr` and `cnpj` are aliases of their schemes. `None`: no class, or a scheme the registry
/// does not have.
#[uniffi::export]
pub fn badge_class(level: BadgeLevel, scheme: Option<String>, issuer_registry_json: String) -> Option<BadgeClass> {
    let registry = Registry::parse(&issuer_registry_json)?;
    let found = match level {
        BadgeLevel::Person | BadgeLevel::Org => registry.schemes.iter().find(|s| Some(&s.id) == scheme.as_ref()),
        _ => registry.schemes.iter().find(|s| s.alias.as_deref() == Some(level_name(level))),
    }?;
    Some(BadgeClass {
        class: found.class.clone(),
        assurance: found.assurance.clone(),
        country: found.country.clone(),
        scheme: found.id.clone(),
    })
}

fn verified(
    envelope_json: &str,
    sender: &str,
    sender_master_key: Option<&str>,
    registry: Option<&Registry>,
    life_days: u32,
    now: i64,
) -> Result<IssuedBadge, BadgeFailure> {
    use BadgeFailure::*;

    let mut issuer: Option<&Issuer> = None;
    let payload = signed_payload(envelope_json, |envelope, kid| {
        // Step 2. An `iss` that is not a string names no issuer.
        let id = match envelope.get("iss") {
            None => Some(BIPE_ISSUER),
            Some(value) => value.as_str(),
        };
        let found = registry
            .and_then(|r| id.and_then(|id| r.issuers.iter().find(|i| i.id == id)))
            .ok_or(UnknownIssuer)?;
        issuer = Some(found);
        // Step 3.
        found.keys.iter().find(|(k, _)| k == kid).map(|(_, key)| *key).ok_or(UnknownKid)
    })?;
    let (Some(issuer), Some(registry)) = (issuer, registry) else { return Err(UnknownIssuer) };

    // Step 5.
    let claims = parse_claims(&payload, true).ok_or(Malformed)?;
    // Step 6.
    if claims.iss != issuer.id {
        return Err(IssuerMismatch);
    }
    // Step 7.
    if !issuer.levels.iter().any(|l| l == level_name(claims.level)) {
        return Err(LevelNotAllowed);
    }
    // Step 8.
    if matches!(claims.level, BadgeLevel::Person | BadgeLevel::Org) {
        let scheme = registry.schemes.iter().find(|s| Some(&s.id) == claims.scheme.as_ref()).ok_or(UnknownScheme)?;
        if !issuer.schemes.contains(&scheme.id) {
            return Err(SchemeNotAllowed);
        }
        let same = scheme.class == level_name(claims.level)
            && Some(&scheme.country) == claims.country.as_ref()
            && Some(&scheme.assurance) == claims.assurance.as_ref();
        if !same {
            return Err(SchemeMismatch);
        }
    }
    // Step 9. `parse_claims` keeps both times in range, so the difference cannot overflow.
    if issuer.id != BIPE_ISSUER && claims.exp - claims.iat > i64::from(life_days) * 86_400 {
        return Err(LifeTooLong);
    }
    // Step 10.
    check_time(&claims, now)?;
    // Step 11.
    if claims.sub != sender {
        return Err(SubjectMismatch);
    }
    // Step 12. A server in no region is also a mismatch.
    let server = user_id_server(claims.sub.clone());
    let region = registry.regions.iter().find(|r| server.as_ref().is_some_and(|s| r.servers.contains(s)));
    if region.is_none_or(|r| r.id != issuer.region) {
        return Err(RegionMismatch);
    }
    // Step 13. No known master key fails closed, and `msk` is never empty here.
    if sender_master_key != Some(claims.msk.as_str()) {
        return Err(MasterKeyMismatch);
    }
    Ok(IssuedBadge { claims, issuer_name: issuer.name.clone(), issuer_region: issuer.region.clone() })
}

fn level_name(level: BadgeLevel) -> &'static str {
    match level {
        BadgeLevel::Pix => "pix",
        BadgeLevel::Govbr => "govbr",
        BadgeLevel::Institution => "institution",
        BadgeLevel::Cnpj => "cnpj",
        BadgeLevel::BrandMark => "brand-mark",
        BadgeLevel::Person => "person",
        BadgeLevel::Org => "org",
    }
}

/// The parts of an issuer registry payload that verification reads. Only `valid` issuers.
struct Registry {
    issuers: Vec<Issuer>,
    schemes: Vec<Scheme>,
    regions: Vec<Region>,
}

struct Issuer {
    id: String,
    name: String,
    region: String,
    levels: Vec<String>,
    schemes: Vec<String>,
    keys: Vec<(String, VerifyingKey)>,
}

struct Scheme {
    id: String,
    country: String,
    class: String,
    assurance: String,
    alias: Option<String>,
}

struct Region {
    id: String,
    servers: Vec<String>,
}

impl Registry {
    /// `None` when the text is not a registry. The signed-list verifier checked the full shape before.
    fn parse(json: &str) -> Option<Registry> {
        let payload: Map<String, Value> = serde_json::from_str(json).ok()?;
        let text = |o: &Map<String, Value>, key: &str| o.get(key).and_then(Value::as_str).map(str::to_owned);
        let texts = |o: &Map<String, Value>, key: &str| -> Option<Vec<String>> {
            o.get(key)?.as_array()?.iter().map(|v| v.as_str().map(str::to_owned)).collect()
        };
        let objects = |key: &str| -> Option<Vec<&Map<String, Value>>> {
            payload.get(key)?.as_array()?.iter().map(Value::as_object).collect()
        };

        let mut issuers = Vec::new();
        for i in objects("issuers")? {
            if i.get("status").and_then(Value::as_str) != Some("valid") {
                continue;
            }
            let keys = i
                .get("keys")?
                .as_object()?
                .iter()
                .map(|(kid, key)| {
                    let raw = URL_SAFE_NO_PAD.decode(key.as_str()?).ok()?;
                    let key = VerifyingKey::from_bytes(&<[u8; 32]>::try_from(raw.as_slice()).ok()?).ok()?;
                    Some((kid.clone(), key))
                })
                .collect::<Option<Vec<_>>>()?;
            issuers.push(Issuer {
                id: text(i, "id")?,
                name: text(i, "name")?,
                region: text(i, "region")?,
                levels: texts(i, "levels")?,
                schemes: texts(i, "schemes")?,
                keys,
            });
        }
        let schemes = objects("schemes")?
            .into_iter()
            .map(|s| {
                Some(Scheme {
                    id: text(s, "id")?,
                    country: text(s, "country")?,
                    class: text(s, "class")?,
                    assurance: text(s, "assurance")?,
                    alias: text(s, "alias"),
                })
            })
            .collect::<Option<Vec<_>>>()?;
        let regions = objects("regions")?
            .into_iter()
            .map(|r| Some(Region { id: text(r, "id")?, servers: texts(r, "servers")? }))
            .collect::<Option<Vec<_>>>()?;
        Some(Registry { issuers, schemes, regions })
    }
}
