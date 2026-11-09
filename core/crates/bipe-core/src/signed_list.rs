// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The signed-list verifier: the operator list and the issuer registry
//! (bipe-shared docs/signed-lists.md, bipe-chat/bipe#645 step 5, bipe-chat/bipe#637).
//! The reference is bipe-shared tools/lib/signed-lists.mjs. Its JavaScript semantics decide every edge case here.
//! No production client calls this code until bipe-chat/bipe#1277 closes (ADR 0267).

use crate::badge::{TrustedKey, is_scheme_id};
use base64::Engine;
use base64::alphabet;
use base64::engine::{DecodePaddingMode, GeneralPurpose, GeneralPurposeConfig};
use ed25519_dalek::{Signature, VerifyingKey};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};

/// The reference decodes with Node `base64url`: the URL alphabet, no padding, any trailing bits.
const BASE64URL: GeneralPurpose = GeneralPurpose::new(
    &alphabet::URL_SAFE,
    GeneralPurposeConfig::new()
        .with_decode_allow_trailing_bits(true)
        .with_decode_padding_mode(DecodePaddingMode::RequireNone),
);
const BASE64URL_ENCODE: GeneralPurpose = GeneralPurpose::new(&alphabet::URL_SAFE, GeneralPurposeConfig::new().with_encode_padding(false));

const MAX_TIME: f64 = 253_402_300_799.0;
/// JavaScript `Number.MAX_SAFE_INTEGER`.
const MAX_VERSION: f64 = 9_007_199_254_740_991.0;
const LEVELS: [&str; 7] = ["pix", "govbr", "institution", "cnpj", "brand-mark", "person", "org"];

/// The one server that counts when a device has no current operator list, or the list is stale
/// (docs/signed-lists.md "Bundled fallback", the A-3 default). bipe-shared registries/bipe-servers.json
/// holds the same name. A test compares the two.
pub const FALLBACK_BIPE_SERVER: &str = "bipe.net.br";

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ListType {
    Operators,
    Issuers,
}

/// The public keys that may sign one list type, and how many different keys must sign.
#[derive(Clone, Debug, uniffi::Record)]
pub struct SignerSet {
    /// A threshold of 0 accepts no list (fail closed).
    pub threshold: u32,
    pub keys: Vec<TrustedKey>,
}

/// The list a device holds: its version and the `payload` text of its envelope, exactly as received.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct StoredList {
    pub version: u64,
    pub payload: String,
}

/// The first failed step of "Verification order". `list_failure_code` gives the code of the spec and the vectors.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ListFailure {
    UnsupportedVersion,
    InvalidSignature,
    WrongType,
    Malformed,
    Expired,
    RolledBack,
    BrokenChain,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum OperatorRegionFloor {
    E2ee,
    /// `none`: the region runs alone and does not federate.
    NoE2ee,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct OperatorRegion {
    pub id: String,
    pub name: String,
    pub core_api: String,
    pub floor: OperatorRegionFloor,
}

/// One `kid` and its public key text, as in the payload. Signing keys are unpadded base64,
/// report and appeal keys are base64url.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ListKey {
    pub kid: String,
    pub key: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum OperatorSignup {
    Open,
    Invite,
    Closed,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum OperatorState {
    Valid,
    Removed,
}

/// One operator entry. A `removed` entry has no keys, URL, egress or sign-up value (A-5).
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct ListedOperator {
    pub server_name: String,
    pub name: String,
    pub state: OperatorState,
    pub joined: String,
    pub removed: Option<String>,
    pub signing_keys: Vec<ListKey>,
    pub account_api: Option<String>,
    pub push_egress: Vec<String>,
    pub signup: Option<OperatorSignup>,
    pub report_keys: Vec<ListKey>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct OperatorList {
    pub region: OperatorRegion,
    pub regions: Vec<OperatorRegion>,
    pub appeal_keys: Vec<ListKey>,
    pub operators: Vec<ListedOperator>,
}

/// A list that passed every step. Store `stored`; give it back as `stored` on the next fetch.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct VerifiedList {
    pub list_type: ListType,
    pub version: u64,
    pub issued: i64,
    pub expires: i64,
    pub stored: StoredList,
    /// Set for `ListType::Operators`.
    pub operators: Option<OperatorList>,
    /// The payload JSON text. The issuer registry has no typed form yet (bipe-chat/bipe#637).
    pub payload_json: String,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SignedListResult {
    Valid { list: VerifiedList },
    Invalid { failure: ListFailure },
}

/// The answer of the rule "who is a Bipe user" for one server name.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum BipeServerStatus {
    Valid,
    Removed,
    Unknown,
}

/// docs/signed-lists.md "Verification order", steps 1 to 6. `now_seconds` is Unix seconds.
/// `stored` is the list the device holds, or none. Fail closed: on `Invalid`, keep the stored list.
#[uniffi::export]
pub fn verify_signed_list(
    envelope_json: String,
    list_type: ListType,
    signer_set: SignerSet,
    now_seconds: i64,
    stored: Option<StoredList>,
) -> SignedListResult {
    into_result(verified(&envelope_json, list_type, &signer_set, Some(now_seconds), stored.as_ref()))
}

/// The bundled list inside an app build: steps 1 to 4 only. An old build must still start, so
/// expiry is not checked. A stale bundled list follows the stale rule of `bipe_server_status`.
#[uniffi::export]
pub fn verify_bundled_list(envelope_json: String, list_type: ListType, signer_set: SignerSet) -> SignedListResult {
    into_result(verified(&envelope_json, list_type, &signer_set, None, None))
}

/// The code of a failure in the spec and in test-vectors/signed-lists.json.
#[uniffi::export]
pub fn list_failure_code(failure: ListFailure) -> String {
    match failure {
        ListFailure::UnsupportedVersion => "unsupported_version",
        ListFailure::InvalidSignature => "invalid_signature",
        ListFailure::WrongType => "wrong_type",
        ListFailure::Malformed => "malformed",
        ListFailure::Expired => "expired",
        ListFailure::RolledBack => "rolled_back",
        ListFailure::BrokenChain => "broken_chain",
    }
    .to_owned()
}

/// `valid`, or the failure code. The vector harnesses use it on every client.
#[uniffi::export]
pub fn signed_list_result_code(result: SignedListResult) -> String {
    match result {
        SignedListResult::Valid { .. } => "valid".to_owned(),
        SignedListResult::Invalid { failure } => list_failure_code(failure),
    }
}

/// `operatorStatus` of the reference: the entry whose `server_name` equals the lower-case `server_name`.
#[uniffi::export]
pub fn operator_status(list: OperatorList, server_name: String) -> BipeServerStatus {
    lookup(&list, &server_name)
}

/// `federatedRegions` of the reference: the region of the list and the other regions, floor `e2ee` only.
#[uniffi::export]
pub fn federated_regions(list: OperatorList) -> Vec<String> {
    std::iter::once(&list.region).chain(&list.regions).filter(|r| r.floor == OperatorRegionFloor::E2ee).map(|r| r.id.clone()).collect()
}

/// The status of a server for a device. With no current operator list, or a stale one
/// (`now_seconds >= expires`), only `FALLBACK_BIPE_SERVER` is `valid` (the A-3 default).
#[uniffi::export]
pub fn bipe_server_status(current: Option<VerifiedList>, server_name: String, now_seconds: i64) -> BipeServerStatus {
    match current.filter(|list| now_seconds < list.expires).and_then(|list| list.operators) {
        Some(list) => lookup(&list, &server_name),
        None if server_name == FALLBACK_BIPE_SERVER => BipeServerStatus::Valid,
        None => BipeServerStatus::Unknown,
    }
}

/// The server part of a Matrix user ID, by the rule of bipe-shared `isBipeUserID`: `@`, a localpart
/// of at least one character, then everything after the first `:`. `None` is not a user ID.
#[uniffi::export]
pub fn user_id_server(user_id: String) -> Option<String> {
    let colon = user_id.find(':')?;
    (user_id.starts_with('@') && colon >= 2).then(|| user_id[colon + 1..].to_owned())
}

/// The SHA-256 of payload bytes, base64url without padding: the `prev` of the next version.
#[uniffi::export]
pub fn list_hash(payload_bytes: Vec<u8>) -> String {
    BASE64URL_ENCODE.encode(Sha256::digest(&payload_bytes))
}

fn into_result(verdict: Result<VerifiedList, ListFailure>) -> SignedListResult {
    match verdict {
        Ok(list) => SignedListResult::Valid { list },
        Err(failure) => SignedListResult::Invalid { failure },
    }
}

/// Exact match, case kept: `server_name` in a list is lower case, so `BIPE.NET.BR` is `unknown`.
fn lookup(list: &OperatorList, server_name: &str) -> BipeServerStatus {
    match list.operators.iter().find(|o| o.server_name == server_name).map(|o| o.state) {
        Some(OperatorState::Valid) => BipeServerStatus::Valid,
        Some(OperatorState::Removed) => BipeServerStatus::Removed,
        None => BipeServerStatus::Unknown,
    }
}

/// `now: None` skips step 5 (the bundled list).
fn verified(
    envelope_json: &str,
    list_type: ListType,
    signer_set: &SignerSet,
    now: Option<i64>,
    stored: Option<&StoredList>,
) -> Result<VerifiedList, ListFailure> {
    use ListFailure::*;

    // Step 1. Text that is not a JSON object has no `v` of 1. JavaScript `1.0 === 1`.
    let envelope = match serde_json::from_str::<Value>(envelope_json) {
        Ok(Value::Object(envelope)) => envelope,
        _ => return Err(UnsupportedVersion),
    };
    if envelope.get("v").and_then(Value::as_f64) != Some(1.0) {
        return Err(UnsupportedVersion);
    }

    // Step 2. The threshold rule over the exact payload bytes.
    let payload_text = envelope.get("payload").and_then(Value::as_str);
    let bytes = payload_text.and_then(b64url);
    let signed = match (&bytes, envelope.get("sigs")) {
        (Some(bytes), Some(Value::Array(sigs))) => signer_count(bytes, sigs, signer_set),
        _ => 0,
    };
    if signer_set.threshold == 0 || signed < signer_set.threshold as usize {
        return Err(InvalidSignature);
    }
    let (Some(payload_text), Some(bytes)) = (payload_text, bytes) else { return Err(InvalidSignature) };

    // Step 3. The reference's TextDecoder drops one leading byte order mark.
    let mut json = String::from_utf8(bytes).map_err(|_| Malformed)?;
    if json.starts_with('\u{FEFF}') {
        json.remove(0);
    }
    let payload = match serde_json::from_str::<Value>(&json).map_err(|_| Malformed)? {
        Value::Object(payload) => payload,
        _ => return Err(WrongType),
    };
    let typ = match list_type {
        ListType::Operators => "operators",
        ListType::Issuers => "issuers",
    };
    if payload.get("typ").and_then(Value::as_str) != Some(typ) {
        return Err(WrongType);
    }

    // Step 4.
    let common = common_fields(&payload).ok_or(Malformed)?;
    let operators = match list_type {
        ListType::Operators => Some(operator_list(&payload).ok_or(Malformed)?),
        ListType::Issuers => {
            issuers_well_formed(&payload).ok_or(Malformed)?;
            None
        }
    };

    // Step 5.
    if now.is_some_and(|now| now >= common.expires) {
        return Err(Expired);
    }

    // Step 6. Equal versions compare the payload text, as the reference does.
    if let Some(stored) = stored {
        if common.version < stored.version || (common.version == stored.version && payload_text != stored.payload) {
            return Err(RolledBack);
        }
        if common.version == stored.version + 1 {
            let previous = b64url(&stored.payload).map(list_hash);
            if previous.is_none() || common.prev.as_deref() != previous.as_deref() {
                return Err(BrokenChain);
            }
        }
    }

    Ok(VerifiedList {
        list_type,
        version: common.version,
        issued: common.issued,
        expires: common.expires,
        stored: StoredList { version: common.version, payload: payload_text.to_owned() },
        operators,
        payload_json: json,
    })
}

/// The number of different signer keys with a valid signature. A `kid` outside the set is ignored,
/// a signature that fails does not count, and one key counts once, also under two `kid`s.
fn signer_count(bytes: &[u8], sigs: &[Value], signer_set: &SignerSet) -> usize {
    let mut keys = HashSet::new();
    for sig in sigs {
        let Some(sig) = sig.as_object() else { continue };
        let Some(kid) = sig.get("kid").and_then(Value::as_str) else { continue };
        let Some(key) = signer_set.keys.iter().find(|key| key.kid == kid) else { continue };
        let Ok(public_key) = <[u8; 32]>::try_from(key.public_key.as_slice()) else { continue };
        let Ok(verifying_key) = VerifyingKey::from_bytes(&public_key) else { continue };
        let Some(raw) = sig.get("sig").and_then(Value::as_str).and_then(b64url) else { continue };
        let Ok(raw) = <[u8; 64]>::try_from(raw.as_slice()) else { continue };
        // Strict verification, as for badges.
        if verifying_key.verify_strict(bytes, &Signature::from_bytes(&raw)).is_ok() {
            keys.insert(public_key);
        }
    }
    keys.len()
}

/// `b64urlDecode` of the reference: only the URL alphabet, and no padding.
fn b64url(text: &str) -> Option<Vec<u8>> {
    if !text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return None;
    }
    // Node drops a last lone character; the base64 crate rejects it.
    let usable = if text.len() % 4 == 1 { &text[..text.len() - 1] } else { text };
    BASE64URL.decode(usable).ok()
}

struct Common {
    version: u64,
    issued: i64,
    expires: i64,
    prev: Option<String>,
}

/// JavaScript `Number.isInteger`: any JSON number with no fraction, `3.0` and `3e2` too.
fn integer(value: Option<&Value>, max: f64) -> Option<f64> {
    value.and_then(Value::as_f64).filter(|n| n.fract() == 0.0 && (0.0..=max).contains(n))
}

fn common_fields(p: &Map<String, Value>) -> Option<Common> {
    let version = integer(p.get("version"), MAX_VERSION).filter(|v| *v >= 1.0)?;
    let issued = integer(p.get("issued"), MAX_TIME)?;
    let expires = integer(p.get("expires"), MAX_TIME)?;
    let prev = match (version == 1.0, p.get("prev")) {
        (true, Some(Value::Null)) => None,
        (false, Some(Value::String(prev))) if prev.len() == 43 && b64url_chars(prev) => Some(prev.clone()),
        _ => return None,
    };
    Some(Common { version: version as u64, issued: issued as i64, expires: expires as i64, prev })
}

fn b64url_chars(text: &str) -> bool {
    text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// `text` of the reference: a string that is not empty.
fn text(value: Option<&Value>) -> Option<&str> {
    value.and_then(Value::as_str).filter(|s| !s.is_empty())
}

/// `^\d{4}-\d{2}-\d{2}$`
fn date(value: Option<&Value>) -> Option<String> {
    let s = value.and_then(Value::as_str)?;
    let b = s.as_bytes();
    let ok = b.len() == 10
        && b.iter().enumerate().all(|(i, c)| if i == 4 || i == 7 { *c == b'-' } else { c.is_ascii_digit() });
    ok.then(|| s.to_owned())
}

fn https(value: Option<&Value>) -> Option<String> {
    value.and_then(Value::as_str).filter(|s| s.starts_with("https://")).map(str::to_owned)
}

/// `keyMap` of the reference: an object, not empty, each `kid` unique in `seen`, each key 32 raw bytes.
fn key_map(value: Option<&Value>, seen: &mut HashSet<String>) -> Option<Vec<ListKey>> {
    let keys = value.and_then(Value::as_object).filter(|keys| !keys.is_empty())?;
    keys.iter()
        .map(|(kid, key)| {
            let key = key.as_str()?;
            (!kid.is_empty() && seen.insert(kid.clone()) && b64url(key).is_some_and(|raw| raw.len() == 32))
                .then(|| ListKey { kid: kid.clone(), key: key.to_owned() })
        })
        .collect()
}

fn region(value: &Value) -> Option<OperatorRegion> {
    let r = value.as_object()?;
    let id = r.get("id").and_then(Value::as_str).filter(|id| id.len() >= 2 && id.bytes().all(|b| b.is_ascii_lowercase()))?;
    let floor = match r.get("floor").and_then(Value::as_str)? {
        "e2ee" => OperatorRegionFloor::E2ee,
        "none" => OperatorRegionFloor::NoE2ee,
        _ => return None,
    };
    Some(OperatorRegion { id: id.to_owned(), name: text(r.get("name"))?.to_owned(), core_api: https(r.get("core_api"))?, floor })
}

const REMOVED_OPERATOR_ABSENT: [&str; 5] = ["signing_keys", "account_api", "push_egress", "signup", "report_keys"];

fn operator_list(p: &Map<String, Value>) -> Option<OperatorList> {
    let own = region(p.get("region")?)?;
    let regions = p.get("regions")?.as_array()?.iter().map(region).collect::<Option<Vec<_>>>()?;
    let mut ids = HashSet::from([own.id.as_str()]);
    if !regions.iter().all(|r| ids.insert(r.id.as_str())) {
        return None;
    }
    let entries = p.get("operators")?.as_array()?;
    let mut report_kids = HashSet::new();
    let appeal_keys = key_map(p.get("appeal_keys"), &mut report_kids)?;
    let mut servers = HashSet::new();
    let mut operators = Vec::with_capacity(entries.len());
    for entry in entries {
        let o = entry.as_object()?;
        let server_name = text(o.get("server_name")).filter(|s| *s == s.to_lowercase())?;
        if !servers.insert(server_name) {
            return None;
        }
        let name = text(o.get("name"))?.to_owned();
        let joined = date(o.get("joined"))?;
        let operator = match o.get("status").and_then(Value::as_str)? {
            "removed" => {
                let removed = date(o.get("removed"))?;
                if REMOVED_OPERATOR_ABSENT.iter().any(|k| o.contains_key(*k)) {
                    return None;
                }
                ListedOperator {
                    server_name: server_name.to_owned(),
                    name,
                    state: OperatorState::Removed,
                    joined,
                    removed: Some(removed),
                    signing_keys: Vec::new(),
                    account_api: None,
                    push_egress: Vec::new(),
                    signup: None,
                    report_keys: Vec::new(),
                }
            }
            "valid" => {
                // The reference checks only that `signing_keys` is an object with a key; the values stay text.
                let signing = o.get("signing_keys").and_then(Value::as_object).filter(|k| !k.is_empty())?;
                let signing_keys = signing
                    .iter()
                    .map(|(kid, key)| ListKey { kid: kid.clone(), key: key.as_str().map(str::to_owned).unwrap_or_else(|| key.to_string()) })
                    .collect();
                let egress = o.get("push_egress")?.as_array().filter(|e| !e.is_empty())?;
                let push_egress = egress.iter().map(|e| text(Some(e)).map(str::to_owned)).collect::<Option<Vec<_>>>()?;
                let signup = match o.get("signup").and_then(Value::as_str)? {
                    "open" => OperatorSignup::Open,
                    "invite" => OperatorSignup::Invite,
                    "closed" => OperatorSignup::Closed,
                    _ => return None,
                };
                ListedOperator {
                    server_name: server_name.to_owned(),
                    name,
                    state: OperatorState::Valid,
                    joined,
                    removed: None,
                    signing_keys,
                    account_api: Some(https(o.get("account_api"))?),
                    push_egress,
                    signup: Some(signup),
                    report_keys: key_map(o.get("report_keys"), &mut report_kids)?,
                }
            }
            _ => return None,
        };
        operators.push(operator);
    }
    Some(OperatorList { region: own, regions, appeal_keys, operators })
}

const REMOVED_ISSUER_ABSENT: [&str; 4] = ["region", "levels", "schemes", "keys"];

/// `issuersWellFormed` of the reference.
fn issuers_well_formed(p: &Map<String, Value>) -> Option<()> {
    let (scheme_list, region_list, issuer_list) =
        (p.get("schemes")?.as_array()?, p.get("regions")?.as_array()?, p.get("issuers")?.as_array()?);

    let mut schemes: HashMap<&str, &Map<String, Value>> = HashMap::new();
    for s in scheme_list {
        let s = s.as_object()?;
        let id = s.get("id").and_then(Value::as_str).filter(|id| is_scheme_id(id))?;
        let country = s.get("country").and_then(Value::as_str)?;
        let class = s.get("class").and_then(Value::as_str)?;
        let ok = country.len() == 2
            && country.bytes().all(|b| b.is_ascii_uppercase())
            && (class == "person" || class == "org")
            && text(s.get("assurance")).is_some();
        if !ok || schemes.insert(id, s).is_some() {
            return None;
        }
    }

    let mut regions: HashMap<&str, Vec<&str>> = HashMap::new();
    let (mut servers, mut owned) = (HashSet::new(), HashSet::new());
    for r in region_list {
        let r = r.as_object()?;
        let id = r.get("id").and_then(Value::as_str)?;
        let (region_servers, region_schemes) = (r.get("servers")?.as_array()?, r.get("schemes")?.as_array()?);
        if regions.contains_key(id) {
            return None;
        }
        for s in region_servers {
            if !servers.insert(text(Some(s))?) {
                return None;
            }
        }
        let mut ids = Vec::new();
        for s in region_schemes {
            let s = s.as_str()?;
            if !schemes.contains_key(s) || !owned.insert(s) {
                return None;
            }
            ids.push(s);
        }
        regions.insert(id, ids);
    }

    let (mut ids, mut kids) = (HashSet::new(), HashSet::new());
    for i in issuer_list {
        let i = i.as_object()?;
        let id = i.get("id").and_then(Value::as_str).filter(|id| is_issuer_id(id))?;
        if !ids.insert(id) {
            return None;
        }
        text(i.get("name"))?;
        date(i.get("accredited"))?;
        match i.get("status").and_then(Value::as_str)? {
            "removed" => {
                date(i.get("removed"))?;
                if REMOVED_ISSUER_ABSENT.iter().any(|k| i.contains_key(*k)) {
                    return None;
                }
                continue;
            }
            "valid" => {}
            _ => return None,
        }
        let region_schemes = regions.get(i.get("region").and_then(Value::as_str)?)?;
        let levels = i.get("levels")?.as_array().filter(|l| !l.is_empty())?;
        let levels = levels.iter().map(|l| l.as_str().filter(|l| LEVELS.contains(l))).collect::<Option<Vec<_>>>()?;
        let held = i.get("schemes")?.as_array().filter(|s| !s.is_empty())?;
        let held = held
            .iter()
            .map(|s| s.as_str().filter(|s| region_schemes.contains(s)).and_then(|s| schemes.get(s).copied()))
            .collect::<Option<Vec<_>>>()?;
        let field = |s: &Map<String, Value>, key: &str, value: &str| s.get(key).and_then(Value::as_str) == Some(value);
        for level in levels {
            if level == "person" || level == "org" {
                if !held.iter().any(|s| field(*s, "class", level)) {
                    return None;
                }
            } else if schemes.values().any(|s| field(*s, "alias", level)) && !held.iter().any(|s| field(*s, "alias", level)) {
                return None;
            }
        }
        key_map(i.get("keys"), &mut kids)?;
    }
    Some(())
}

/// `^[a-z0-9][a-z0-9-]{1,31}$`
fn is_issuer_id(id: &str) -> bool {
    let b = id.as_bytes();
    let tail = |c: &u8| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-';
    (2..=32).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b[1..].iter().all(tail)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_id_server_follows_is_bipe_user_id() {
        assert_eq!(user_id_server("@ana:bipe.net.br:8448".into()).as_deref(), Some("bipe.net.br:8448"));
        assert_eq!(user_id_server("@:bipe.net.br".into()), None);
        assert_eq!(user_id_server("ana:bipe.net.br".into()), None);
        assert_eq!(user_id_server("@ana".into()), None);
    }

    #[test]
    fn no_list_counts_only_the_fallback_server() {
        assert_eq!(bipe_server_status(None, FALLBACK_BIPE_SERVER.into(), 0), BipeServerStatus::Valid);
        assert_eq!(bipe_server_status(None, "coop.exemplo.br".into(), 0), BipeServerStatus::Unknown);
    }

    #[test]
    fn a_lone_last_character_decodes_like_node() {
        assert_eq!(b64url("QUJD"), Some(b"ABC".to_vec()));
        assert_eq!(b64url("QUJDR"), Some(b"ABC".to_vec()));
        assert_eq!(b64url("QUJD="), None);
    }

    #[test]
    fn a_zero_threshold_accepts_nothing() {
        let set = SignerSet { threshold: 0, keys: Vec::new() };
        let envelope = r#"{"v":1,"payload":"e30","sigs":[]}"#.to_owned();
        assert_eq!(signed_list_result_code(verify_bundled_list(envelope, ListType::Operators, set)), "invalid_signature");
    }
}
