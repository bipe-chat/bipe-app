// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

//! bipe-shared test-vectors/pix-check.json, field `code`, against the crate. BIPE_SHARED sets the bipe-shared path.

use bipe_core::*;
use serde_json::{Value, json};
use std::path::PathBuf;

fn shared(path: &str) -> Value {
    let root = std::env::var("BIPE_SHARED")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../bipe-shared"));
    serde_json::from_str(&std::fs::read_to_string(root.join(path)).expect("bipe-shared file")).expect("JSON")
}

fn status(status: PixCodeStatus) -> &'static str {
    match status {
        PixCodeStatus::Valid => "valid",
        PixCodeStatus::BadCrc => "badCrc",
        PixCodeStatus::Malformed => "malformed",
    }
}

fn key_type(key_type: PixKeyType) -> &'static str {
    match key_type {
        PixKeyType::Cpf => "cpf",
        PixKeyType::Cnpj => "cnpj",
        PixKeyType::Phone => "phone",
        PixKeyType::Email => "email",
        PixKeyType::Random => "random",
        PixKeyType::Unknown => "unknown",
    }
}

/// The reference's object: only `status` when the code is malformed.
fn as_json(code: &PixCode) -> Value {
    if code.status == PixCodeStatus::Malformed {
        return json!({ "status": "malformed" });
    }
    json!({
        "status": status(code.status),
        "dynamic": code.dynamic,
        "singleUse": code.single_use,
        "key": code.key,
        "keyType": code.key_type.map(key_type),
        "url": code.url,
        "name": code.name,
        "city": code.city,
        "amount": code.amount,
        "txid": code.txid,
        "info": code.info,
    })
}

#[test]
fn every_code_vector_passes() {
    let file = shared("test-vectors/pix-check.json");
    let vectors = file["vectors"].as_array().unwrap();
    assert!(!vectors.is_empty());
    for vector in vectors {
        let text = vector["text"].as_str().unwrap();
        let found = find_pix_code(text.to_owned()).map(|m| as_json(&m.code)).unwrap_or(Value::Null);
        assert_eq!(found, vector["code"], "{text}");
    }
}

/// The span is in code points and ends after the CRC field: the text around it is what the app checks for keys.
#[test]
fn span_leaves_the_text_around_the_code() {
    let file = shared("test-vectors/pix-check.json");
    let code = file["vectors"][0]["text"].as_str().unwrap();
    let text = format!("Olá 😀 {code} chave 12345678901");
    let found = find_pix_code(text.clone()).unwrap();
    let points: Vec<char> = text.chars().collect();
    assert_eq!(points[..found.start as usize].iter().collect::<String>(), "Olá 😀 ");
    assert_eq!(points[found.end as usize..].iter().collect::<String>(), " chave 12345678901");
}

#[test]
fn key_types() {
    for (key, expected) in [
        ("fulano@example.com", "email"),
        ("+5511987654321", "phone"),
        ("+551198765432", "phone"),
        ("5511987654321", "unknown"),
        ("123e4567-e12b-12d1-a456-426655440000", "random"),
        ("12345678901", "cpf"),
        ("12ABC34501DE35", "cnpj"),
        ("12abc34501de35", "unknown"),
        ("", "unknown"),
    ] {
        assert_eq!(key_type(pix_key_type(key.to_owned())), expected, "{key}");
    }
}

#[test]
fn crc_of_the_reference() {
    // CRC-16/CCITT-FALSE check value.
    assert_eq!(pix_crc16("123456789".to_owned()), "29B1");
    assert_eq!(pix_crc16(String::new()), "FFFF");
}
