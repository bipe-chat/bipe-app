// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/person-proofs.json against the crate, with the schemes of registries/schemes.json in an
//! issuer registry payload. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn level(name: &str) -> Option<BadgeLevel> {
    Some(match name {
        "pix" => BadgeLevel::Pix,
        "govbr" => BadgeLevel::Govbr,
        "institution" => BadgeLevel::Institution,
        "cnpj" => BadgeLevel::Cnpj,
        "brand-mark" => BadgeLevel::BrandMark,
        "person" => BadgeLevel::Person,
        "org" => BadgeLevel::Org,
        _ => return None,
    })
}

#[test]
fn person_proof_vectors() {
    let schemes = shared("registries/schemes.json")["schemes"].as_array().unwrap().clone();
    let vectors = shared("test-vectors/person-proofs.json");
    for v in vectors["vectors"].as_array().unwrap() {
        // extraSchemes: test-only schemes of another country for this vector.
        let mut all = schemes.clone();
        all.extend(v["extraSchemes"].as_array().cloned().unwrap_or_default());
        let registry = json!({ "issuers": [], "regions": [], "schemes": all }).to_string();
        let badges: Vec<PersonBadge> = v["payloads"]
            .as_array()
            .unwrap()
            .iter()
            .filter_map(|p| {
                Some(PersonBadge { level: level(p["lvl"].as_str()?)?, scheme: p["scheme"].as_str().map(str::to_owned) })
            })
            .collect();
        let got: Vec<Value> = person_proofs(badges, registry)
            .into_iter()
            .map(|p| {
                let mark = match p.mark {
                    SealMark::Fill => "fill",
                    SealMark::Regular => "regular",
                };
                json!({ "scheme": p.scheme, "assurance": p.assurance, "mark": mark })
            })
            .collect();
        assert_eq!(Value::Array(got), v["expected"], "{}", v["name"]);
    }
}

#[test]
fn badge_index_points_at_the_first_badge_of_the_scheme() {
    let schemes = shared("registries/schemes.json")["schemes"].clone();
    let registry = json!({ "issuers": [], "regions": [], "schemes": schemes }).to_string();
    let badges = vec![
        PersonBadge { level: BadgeLevel::Pix, scheme: None },
        PersonBadge { level: BadgeLevel::Person, scheme: Some("br.govbr".into()) },
        PersonBadge { level: BadgeLevel::Govbr, scheme: None },
    ];
    let proofs = person_proofs(badges, registry);
    assert_eq!(proofs.iter().map(|p| p.badge_index).collect::<Vec<_>>(), vec![1, 0]);
    assert_eq!(proofs[0].country, "BR");
}
