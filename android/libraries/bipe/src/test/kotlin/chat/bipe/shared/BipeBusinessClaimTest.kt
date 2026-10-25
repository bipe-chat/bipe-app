// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.api.BipeBusinessClaim
import chat.bipe.shared.api.BipeReservedName
import chat.bipe.shared.company.BipeCnpj
import chat.bipe.shared.company.domain
import chat.bipe.shared.company.normalized
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec business-claim (bipe-chat/bipe#249): CNPJ input, website input, the claim answer, the live company steps. */
class BipeBusinessClaimTest {
    private val vectorsDir = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors")

    @Test fun cnpjSharedVectors() {
        val vectors = Json.parseToJsonElement(File(vectorsDir, "cnpj.json").readText()).jsonObject.getValue("vectors") as JsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val input = vector.getValue("input").jsonPrimitive.content
            val expected = vector["cnpj"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
            assertEquals("normalized($input)", expected, BipeCnpj.normalized(input))
        }
    }

    @Test fun websiteInput() {
        assertEquals("padaria.com.br", BipeCnpj.domain("Padaria.com.br"))
        assertEquals("padaria.com.br", BipeCnpj.domain("https://www.padaria.com.br/contato?x=1"))
        assertEquals("loja.padaria.com.br", BipeCnpj.domain(" loja.padaria.com.br. "))
        for (bad in listOf("", "padaria", "http://", "padaria .com.br", "-padaria.com.br", "padaria.c")) assertNull(bad, BipeCnpj.domain(bad))
    }

    @Test fun claimAnswerDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val claim = json.decodeFromString(
            BipeBusinessClaim.serializer(),
            """{"cnpj":"11222333000181","status":"awaiting_proof","challenge":"bipe-claim:abc","registry":{"active":true,"legal_name":"Padaria Ana Ltda",
               "checked_month":"2026-09"},"domains":[{"domain":"padaria.com.br","txt_name":"_bipe.padaria.com.br","txt_value":"bipe-verify=1"}],
               "reserved_names":[{"name":"padaria","approved":false}]}""",
        )
        assertEquals("awaiting_proof", claim.status)
        assertEquals("bipe-claim:abc", claim.challenge)
        assertEquals("Padaria Ana Ltda", claim.registry?.legalName)
        assertNull(claim.registry?.tradeName)
        assertEquals("_bipe.padaria.com.br", claim.domains.first().txtName)
        assertEquals(listOf(BipeReservedName("padaria", false)), claim.reservedNames)
    }

    @Test fun companyStepsAreTodoWithTheClaimScreen() {
        val none = BipeTrustProfile.create("Padaria", true, "padaria", isCompany = true, claimEnabled = true)
        assertTrue(none.steps.drop(2).all { it.status == BipeTrustProfile.Status.TODO })
        assertFalse(none.liveStepsDone)
        val active = BipeTrustProfile.create("Padaria", true, "padaria", isCompany = true, business = BipeBusinessClaim("awaiting_proof"), claimEnabled = true)
        assertEquals(
            listOf(BipeTrustProfile.Status.DONE, BipeTrustProfile.Status.DONE, BipeTrustProfile.Status.DONE, BipeTrustProfile.Status.TODO, BipeTrustProfile.Status.TODO),
            active.steps.map { it.status },
        )
    }
}
