// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeBrandMarkCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The brand mark code out of what a reseller pastes (ADR 0162). Same cases as bipe-api brandMarkCode(). */
class BrandMarkCodeTest {
    @Test
    fun extractsTheCode() {
        val code = "AbCdEfGh12345678"
        assertEquals(code, BipeBrandMarkCode.extract(code))
        assertEquals(code, BipeBrandMarkCode.extract("https://bipe.chat/marca/#$code"))
        assertEquals(code, BipeBrandMarkCode.extract("Oi! Aceita a marca: https://bipe.chat/marca/#$code."))
        assertNull(BipeBrandMarkCode.extract("short"))
        assertNull(BipeBrandMarkCode.extract("${code}x"))
    }
}
