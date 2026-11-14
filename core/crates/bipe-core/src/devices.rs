// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! Devices of an account for Aparelhos e assistentes (ADR 0291, bipe-chat/bipe#1481): device entries in the drafts
//! room, the user-agent rules of an approval entry, and the rows of the list. Reference: deviceInfoFromEvent,
//! userAgentInfo and deviceRows in bipe-shared tools/lib/registries.mjs. Vectors: test-vectors/device-kinds.json.

use crate::generated::registries::*;
use crate::text::{js_trim, utf16_len};
use regex::{Captures, Regex};
use serde_json::Value;
use std::sync::OnceLock;

/// A valid device entry.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AccountDeviceEntry {
    pub device_id: String,
    /// "self" or "approval".
    pub source: String,
    /// A registry kind key other than "unknown".
    pub kind: String,
    pub model: Option<String>,
    pub os: Option<String>,
    pub app: Option<String>,
    pub linked_at: Option<i64>,
    pub linked_by: Option<String>,
    pub attested: bool,
    /// origin_server_ts of the event, 0 when absent.
    pub ts: i64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum DeviceInfoResult {
    Entry { entry: AccountDeviceEntry },
    Hide { reason: String },
}

/// What a user agent says about a device.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct UserAgentInfo {
    pub kind: String,
    pub app: Option<String>,
    pub model: Option<String>,
    pub os: Option<String>,
}

/// One device from GET /_matrix/client/v3/devices.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AccountDevice {
    pub device_id: String,
    pub display_name: Option<String>,
    pub last_seen_ts: Option<i64>,
    pub last_seen_ip: Option<String>,
}

/// One row of the list.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AccountDeviceRow {
    pub device_id: String,
    pub is_this: bool,
    pub kind: String,
    /// Phosphor icon name.
    pub icon: String,
    /// bipe-shared string key.
    pub label: String,
    /// "devices" or "programs".
    pub section: String,
    pub leaves_brazil: bool,
    pub name: Option<String>,
    pub model: Option<String>,
    pub os: Option<String>,
    pub app: Option<String>,
    pub last_seen: Option<i64>,
    pub ip: Option<String>,
    pub linked_at: Option<i64>,
    pub linked_by: Option<String>,
    pub attested: bool,
    pub warning: bool,
    pub can_sign_out: bool,
}

#[derive(Clone, Debug, Default, PartialEq, Eq, uniffi::Record)]
pub struct AccountDeviceCounts {
    pub devices: u32,
    pub programs: u32,
    pub unknown: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct AccountDeviceList {
    pub rows: Vec<AccountDeviceRow>,
    pub counts: AccountDeviceCounts,
}

/// Whether a drafts room event is a valid device entry. `sender_verified` is false when the SDK shows a shield on the
/// event. The reasons are the reference reason ids.
#[uniffi::export]
pub fn device_info_from_event(event_json: String, own_user_id: String, sender_verified: bool) -> DeviceInfoResult {
    let hide = |reason: &str| DeviceInfoResult::Hide { reason: reason.to_owned() };
    let Some(event) = serde_json::from_str::<Value>(&event_json).ok().and_then(|v| v.as_object().cloned()) else { return hide("type") };
    if event.get("type").and_then(Value::as_str) != Some("m.room.message") {
        return hide("type");
    }
    if event.get("redacted").is_some_and(truthy) {
        return hide("redacted");
    }
    if event.get("sender").and_then(Value::as_str) != Some(own_user_id.as_str()) {
        return hide("sender");
    }
    if !sender_verified {
        return hide("unverified");
    }
    let Some(content) = event.get("content").and_then(Value::as_object).filter(|c| c.get("msgtype").and_then(Value::as_str) == Some(DEVICE_INFO_MSGTYPE))
    else {
        return hide("content");
    };
    let Some(c) = content.get(DEVICE_INFO_FIELD).and_then(Value::as_object) else { return hide("field") };
    let Some(device_id) = c.get("device_id").and_then(Value::as_str).filter(|d| !d.is_empty()) else { return hide("device_id") };
    let Some(source) = c.get("source").and_then(Value::as_str).filter(|s| *s == "self" || *s == "approval") else { return hide("source") };
    let Some(kind) = c.get("kind").and_then(Value::as_str).filter(|k| *k != "unknown" && kind_of(k).is_some()) else { return hide("kind") };
    let mut texts: [Option<String>; 4] = Default::default();
    for (i, key) in ["model", "os", "app", "linked_by"].into_iter().enumerate() {
        let Some(v) = c.get(key) else { continue };
        match v.as_str().and_then(device_text) {
            Some(t) => texts[i] = Some(t),
            None => return hide(key),
        }
    }
    let linked_at = match c.get("linked_at") {
        None => None,
        Some(v) => match integer(v) {
            Some(n) if n >= 0 => Some(n),
            _ => return hide("linked_at"),
        },
    };
    let attested = match c.get("attested") {
        None => false,
        Some(Value::Bool(b)) => *b,
        Some(_) => return hide("attested"),
    };
    let [model, os, app, linked_by] = texts;
    DeviceInfoResult::Entry {
        entry: AccountDeviceEntry {
            device_id: device_id.to_owned(),
            source: source.to_owned(),
            kind: kind.to_owned(),
            model,
            os,
            app,
            linked_at,
            linked_by,
            attested,
            ts: event.get("origin_server_ts").and_then(integer).unwrap_or(0),
        },
    }
}

/// What a user agent says about a device, or `None` when no rule matches. The phone that approves a new device writes
/// an approval entry from it.
#[uniffi::export]
pub fn user_agent_info(user_agent: String) -> Option<UserAgentInfo> {
    if user_agent.is_empty() {
        return None;
    }
    let rules = compiled();
    for (regex, kind, app, model, os) in &rules.user_agents {
        let Some(m) = regex.captures(&user_agent) else { continue };
        if *kind == "browser" {
            return Some(UserAgentInfo {
                kind: (*kind).to_owned(),
                app: first_name(&rules.browsers, &user_agent).and_then(|t| device_text(&t)),
                model: None,
                os: first_name(&rules.systems, &user_agent).and_then(|t| device_text(&t)),
            });
        }
        let pick = |t: &Option<&str>| t.and_then(|t| device_text(&fill(t, &m)));
        return Some(UserAgentInfo { kind: (*kind).to_owned(), app: pick(app), model: pick(model), os: pick(os) });
    }
    None
}

/// The rows of Aparelhos e assistentes: this device first, then newest use first. `entries`: valid entries from
/// device_info_from_event; entries of devices that are not in `devices` are ignored.
#[uniffi::export]
pub fn device_rows(this_device_id: String, devices: Vec<AccountDevice>, entries: Vec<AccountDeviceEntry>) -> AccountDeviceList {
    let rank = |e: &AccountDeviceEntry| u8::from(e.source == "self");
    let mut best: Vec<&AccountDeviceEntry> = Vec::new();
    for e in &entries {
        match best.iter().position(|b| b.device_id == e.device_id) {
            None => best.push(e),
            Some(i) => {
                let cur = best[i];
                if rank(e) > rank(cur) || (rank(e) == rank(cur) && e.ts > cur.ts) {
                    best[i] = e;
                }
            }
        }
    }
    let mut rows: Vec<AccountDeviceRow> = devices
        .iter()
        .map(|dev| {
            let e = best.iter().find(|b| b.device_id == dev.device_id).copied();
            let (key, icon, label, section, leaves) = kind_of(e.map_or("unknown", |e| e.kind.as_str())).expect("registry kind");
            let is_this = dev.device_id == this_device_id;
            let name = e
                .and_then(|e| e.model.clone().or_else(|| e.app.clone()))
                .or_else(|| dev.display_name.as_deref().and_then(device_text));
            AccountDeviceRow {
                device_id: dev.device_id.clone(),
                is_this,
                kind: key.to_owned(),
                icon: icon.to_owned(),
                label: label.to_owned(),
                section: section.to_owned(),
                leaves_brazil: leaves,
                name,
                model: e.and_then(|e| e.model.clone()),
                os: e.and_then(|e| e.os.clone()),
                app: e.and_then(|e| e.app.clone()),
                last_seen: dev.last_seen_ts,
                ip: dev.last_seen_ip.clone(),
                linked_at: e.and_then(|e| e.linked_at),
                linked_by: e.and_then(|e| e.linked_by.clone()),
                attested: e.is_some_and(|e| e.attested),
                warning: e.is_none() && !is_this && DEVICE_UNKNOWN_WARNING,
                can_sign_out: !is_this,
            }
        })
        .collect();
    rows.sort_by(|a, b| {
        b.is_this
            .cmp(&a.is_this)
            .then_with(|| b.last_seen.unwrap_or(-1).cmp(&a.last_seen.unwrap_or(-1)))
            .then_with(|| a.device_id.cmp(&b.device_id))
    });
    let mut counts = AccountDeviceCounts::default();
    for r in &rows {
        if r.section == "programs" {
            counts.programs += 1;
        } else {
            counts.devices += 1;
        }
        if r.kind == "unknown" && !r.is_this {
            counts.unknown += 1;
        }
    }
    AccountDeviceList { rows, counts }
}

type KindRow = (&'static str, &'static str, &'static str, &'static str, bool);

fn kind_of(key: &str) -> Option<KindRow> {
    DEVICE_KINDS.iter().find(|k| k.0 == key).copied()
}

/// A trimmed text of 1 to DEVICE_FIELD_MAX_LENGTH UTF-16 units.
fn device_text(v: &str) -> Option<String> {
    let t = js_trim(v);
    (!t.is_empty() && utf16_len(t) <= DEVICE_FIELD_MAX_LENGTH).then(|| t.to_owned())
}

/// A JSON number that is an integer (JavaScript Number.isInteger).
fn integer(v: &Value) -> Option<i64> {
    let f = v.as_f64()?;
    (f.is_finite() && f.fract() == 0.0).then_some(f as i64)
}

/// JavaScript truthiness of a JSON value.
fn truthy(v: &Value) -> bool {
    match v {
        Value::Null => false,
        Value::Bool(b) => *b,
        Value::Number(n) => n.as_f64().is_some_and(|f| f != 0.0 && !f.is_nan()),
        Value::String(s) => !s.is_empty(),
        Value::Array(_) | Value::Object(_) => true,
    }
}

/// $1 to $9 name registry groups; the Rust pattern has the JavaScript match as group 1, so registry group n is n + 1.
fn fill(template: &str, m: &Captures) -> String {
    let mut out = String::new();
    let mut chars = template.chars().peekable();
    while let Some(ch) = chars.next() {
        if ch == '$' {
            if let Some(d) = chars.peek().and_then(|c| c.to_digit(10)).filter(|d| *d >= 1) {
                chars.next();
                out.push_str(m.get(d as usize + 1).map_or("", |g| g.as_str()));
                continue;
            }
        }
        out.push(ch);
    }
    js_trim(&out).to_owned()
}

fn first_name(rules: &[(Regex, &'static str)], text: &str) -> Option<String> {
    rules.iter().find_map(|(regex, name)| regex.captures(text).map(|m| fill(name, &m))).filter(|t| !t.is_empty())
}

type UserAgentRule = (Regex, &'static str, Option<&'static str>, Option<&'static str>, Option<&'static str>);

struct Rules {
    user_agents: Vec<UserAgentRule>,
    browsers: Vec<(Regex, &'static str)>,
    systems: Vec<(Regex, &'static str)>,
}

fn compiled() -> &'static Rules {
    static RULES: OnceLock<Rules> = OnceLock::new();
    RULES.get_or_init(|| Rules {
        user_agents: DEVICE_USER_AGENTS.iter().filter_map(|(p, k, a, m, o)| Regex::new(p).ok().map(|r| (r, *k, *a, *m, *o))).collect(),
        browsers: DEVICE_BROWSERS.iter().filter_map(|(p, n)| Regex::new(p).ok().map(|r| (r, *n))).collect(),
        systems: DEVICE_SYSTEMS.iter().filter_map(|(p, n)| Regex::new(p).ok().map(|r| (r, *n))).collect(),
    })
}
