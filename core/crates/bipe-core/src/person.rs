// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! The person proofs of an account (bipe ADR 0275). Reference: bipe-shared tools/lib/registries.mjs `personProofs`.
//! Vectors: test-vectors/person-proofs.json.

use crate::badge::BadgeLevel;
use crate::generated::registries::PERSON_ASSURANCE;
use crate::issuer::badge_class;

/// A badge that already passed the verifier: its level and, for `person`, its scheme.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PersonBadge {
    pub level: BadgeLevel,
    pub scheme: Option<String>,
}

/// The compact seal mark (ADR 0275): Phosphor seal-check filled or regular.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SealMark {
    Fill,
    Regular,
}

/// One person proof. `badge_index` is the position of the first badge with this scheme in the input.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PersonProof {
    pub scheme: String,
    pub assurance: String,
    pub country: String,
    pub mark: SealMark,
    pub badge_index: u32,
}

/// The person proofs of these badges, strongest first, one per scheme. The schemes come from the issuer registry
/// payload, so a scheme of a new country needs no app release. A badge outside the registry, a company badge or an
/// assurance outside `personAssurance` gives no proof (fail closed). The first proof draws the compact seal.
#[uniffi::export]
pub fn person_proofs(badges: Vec<PersonBadge>, issuer_registry_json: String) -> Vec<PersonProof> {
    let mut proofs: Vec<(usize, PersonProof)> = Vec::new();
    for (index, badge) in badges.into_iter().enumerate() {
        let Some(found) = badge_class(badge.level, badge.scheme, issuer_registry_json.clone()) else { continue };
        if found.class != "person" || proofs.iter().any(|(_, p)| p.scheme == found.scheme) {
            continue;
        }
        let Some(rank) = PERSON_ASSURANCE.iter().position(|(id, _)| *id == found.assurance) else { continue };
        let mark = match PERSON_ASSURANCE[rank].1 {
            "fill" => SealMark::Fill,
            "regular" => SealMark::Regular,
            _ => continue,
        };
        proofs.push((
            rank,
            PersonProof {
                scheme: found.scheme,
                assurance: found.assurance,
                country: found.country,
                mark,
                badge_index: index as u32,
            },
        ));
    }
    // A stable sort keeps the input order within one assurance, as the reference does.
    proofs.sort_by_key(|(rank, _)| *rank);
    proofs.into_iter().map(|(_, proof)| proof).collect()
}
