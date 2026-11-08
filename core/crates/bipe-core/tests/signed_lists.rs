// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/signed-lists.json and bipe-servers.json, and the committed signed lists,
//! against the crate. BIPE_SHARED sets the bipe-shared path.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use bipe_core::*;
use serde_json::Value;
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn signer_set(set: &Value) -> SignerSet {
    SignerSet {
        threshold: set["threshold"].as_u64().unwrap() as u32,
        keys: set["keys"]
            .as_object()
            .unwrap()
            .iter()
            .map(|(kid, key)| TrustedKey { kid: kid.clone(), public_key: URL_SAFE_NO_PAD.decode(key.as_str().unwrap()).unwrap() })
            .collect(),
    }
}

fn list_type(typ: &str) -> ListType {
    match typ {
        "operators" => ListType::Operators,
        "issuers" => ListType::Issuers,
        other => panic!("list type {other}"),
    }
}

fn status_code(status: BipeServerStatus) -> &'static str {
    match status {
        BipeServerStatus::Valid => "valid",
        BipeServerStatus::Removed => "removed",
        BipeServerStatus::Unknown => "unknown",
    }
}

#[test]
fn every_vector() {
    let file = shared("test-vectors/signed-lists.json");
    let now = file["now"].as_i64().unwrap();
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    let (mut lookups, mut federated) = (0, 0);
    for vector in vectors {
        let name = vector["name"].as_str().unwrap();
        let stored = vector["stored"].as_object().map(|s| StoredList {
            version: s["version"].as_u64().unwrap(),
            payload: s["payload"].as_str().unwrap().to_owned(),
        });
        let result = verify_signed_list(
            vector["envelope"].to_string(),
            list_type(vector["typ"].as_str().unwrap()),
            signer_set(&file["signerSets"][vector["signerSet"].as_str().unwrap()]),
            now,
            stored,
        );
        assert_eq!(signed_list_result_code(result.clone()), vector["expect"].as_str().unwrap(), "{name}");

        let SignedListResult::Valid { list } = result else { continue };
        assert_eq!(list.stored.payload, vector["envelope"]["payload"].as_str().unwrap(), "{name}");
        let Some(operators) = list.operators else { continue };
        for lookup in vector["lookups"].as_array().into_iter().flatten() {
            let server = lookup["server"].as_str().unwrap().to_owned();
            assert_eq!(status_code(operator_status(operators.clone(), server)), lookup["expect"].as_str().unwrap(), "{name}");
            lookups += 1;
        }
        if let Some(expected) = vector.get("federated") {
            let expected: Vec<String> = serde_json::from_value(expected.clone()).unwrap();
            assert_eq!(federated_regions(operators.clone()), expected, "{name}");
            federated += 1;
        }
    }
    assert!(lookups > 0 && federated > 0);
}

/// The bundled check skips expiry: an expired vector passes it.
#[test]
fn bundled_list_ignores_expiry() {
    let file = shared("test-vectors/signed-lists.json");
    let vector = file["vectors"].as_array().unwrap().iter().find(|v| v["name"] == "operators: expired").unwrap();
    let set = signer_set(&file["signerSets"]["one-key"]);
    let result = verify_bundled_list(vector["envelope"].to_string(), ListType::Operators, set);
    assert_eq!(signed_list_result_code(result), "valid");
}

/// The committed version 1 lists verify against the committed signer set, and the stale rule applies after `expires`.
#[test]
fn committed_lists_verify() {
    let set = signer_set(&shared("registries/list-signers.json"));
    for (path, typ) in [("signed/operators/1.json", ListType::Operators), ("signed/issuers/1.json", ListType::Issuers)] {
        let result = verify_bundled_list(shared(path).to_string(), typ, set.clone());
        let SignedListResult::Valid { list } = result else { panic!("{path}: {}", signed_list_result_code(result)) };
        assert_eq!(list.version, 1);
        if typ == ListType::Operators {
            let current = Some(list.clone());
            assert_eq!(bipe_server_status(current.clone(), FALLBACK_BIPE_SERVER.into(), list.expires - 1), BipeServerStatus::Valid);
            assert_eq!(bipe_server_status(current, "coop.exemplo.br".into(), list.expires), BipeServerStatus::Unknown);
        }
    }
}

/// `isBipeUserID` cases with no operator list: only the fallback server counts.
#[test]
fn bipe_servers_vectors_without_a_list() {
    let file = shared("test-vectors/bipe-servers.json");
    for vector in file["vectors"].as_array().unwrap() {
        let user_id = vector["userId"].as_str().unwrap();
        let bipe = user_id_server(user_id.to_owned())
            .is_some_and(|server| bipe_server_status(None, server, 0) == BipeServerStatus::Valid);
        assert_eq!(bipe, vector["bipe"].as_bool().unwrap(), "{user_id}");
    }
}

#[test]
fn fallback_server_matches_registry() {
    let registry = shared("registries/bipe-servers.json");
    assert_eq!(registry["servers"], serde_json::json!([FALLBACK_BIPE_SERVER]));
}

#[test]
fn list_hash_chains_versions() {
    let file = shared("test-vectors/signed-lists.json");
    let vector = file["vectors"].as_array().unwrap().iter().find(|v| v["name"] == "operators: valid, replaces a lower version").unwrap();
    let stored = URL_SAFE_NO_PAD.decode(vector["stored"]["payload"].as_str().unwrap()).unwrap();
    let payload: Value =
        serde_json::from_slice(&URL_SAFE_NO_PAD.decode(vector["envelope"]["payload"].as_str().unwrap()).unwrap()).unwrap();
    if payload["version"].as_u64() == vector["stored"]["version"].as_u64().map(|v| v + 1) {
        assert_eq!(payload["prev"].as_str().unwrap(), list_hash(stored));
    }
}
