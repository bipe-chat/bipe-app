// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

@testable import BipeKit
import Testing

/// The brand mark code out of what a reseller pastes (ADR 0162). Same cases as bipe-api brandMarkCode().
struct BrandMarkCodeTests {
    @Test
    func extractsTheCode() {
        let code = "AbCdEfGh12345678"
        #expect(BipeBrandMarkCode.extract(code) == code)
        #expect(BipeBrandMarkCode.extract("https://bipe.chat/marca/#\(code)") == code)
        #expect(BipeBrandMarkCode.extract("Oi! Aceita a marca: https://bipe.chat/marca/#\(code).") == code)
        #expect(BipeBrandMarkCode.extract("short") == nil)
        #expect(BipeBrandMarkCode.extract("\(code)x") == nil)
    }
}
