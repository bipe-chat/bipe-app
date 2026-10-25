// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeKeyAttestation
import chat.bipe.shared.api.BipeApiClient
import chat.bipe.shared.api.BipeAttestation
import chat.bipe.shared.api.BipeSignupRequest
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class KeyAttestationTest {
    private val cert = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x01)

    @Test
    fun encodesEachCertificateAsStandardBase64() {
        assertThat(BipeKeyAttestation.encode(listOf(cert, byteArrayOf(-1, -2)))).containsExactly("MAMCAQE=", "//4=").inOrder()
    }

    @Test
    fun keepsTheContractLimits() {
        assertThat(BipeKeyAttestation.encode(listOf(cert))).isNull()
        assertThat(BipeKeyAttestation.encode(List(9) { cert })).isNull()
        assertThat(BipeKeyAttestation.encode(List(8) { cert })).hasSize(8)
        assertThat(BipeKeyAttestation.encode(listOf(cert, ByteArray(6200)))).isNull()
        assertThat(BipeKeyAttestation.encode(listOf(cert, ByteArray(0)))).isNull()
    }

    @Test
    fun signupRequestSendsTheAndroidForm() {
        val api = BipeApiClient("https://api.example.test")
        val withProof = api.encode(
            BipeSignupRequest.serializer(),
            BipeSignupRequest(pin = "135790", attestation = BipeAttestation(listOf("MAMCAQE=", "//4="), "synthetic-challenge")),
        )
        val attestation = Json.parseToJsonElement(withProof).jsonObject.getValue("attestation").jsonObject
        assertThat(attestation.keys).containsExactly("key_attestation_chain", "challenge")
        assertThat(attestation.getValue("key_attestation_chain").jsonArray.map { it.jsonPrimitive.content })
            .containsExactly("MAMCAQE=", "//4=").inOrder()
        assertThat(attestation.getValue("challenge").jsonPrimitive.content).isEqualTo("synthetic-challenge")
        // A phone that cannot attest sends no attestation at all.
        val without = api.encode(BipeSignupRequest.serializer(), BipeSignupRequest(pin = "135790"))
        assertThat(Json.parseToJsonElement(without).jsonObject.keys).doesNotContain("attestation")
    }

    @Test
    fun neverPrintsTheChain() {
        assertThat(BipeAttestation(listOf("MAMCAQE=", "//4="), "synthetic-challenge").toString()).isEqualTo("BipeAttestation(2 certificates)")
    }
}
