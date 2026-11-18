// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeDeviceKey
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** bipe-shared test-vectors/device-keys.json (ADR 0283, bipe-chat/bipe#1351): the signed message and its signature form. */
class DeviceKeyTest {
    private val vectors: JsonObject by lazy { Json.parseToJsonElement(File(vectorsDir, "device-keys.json").readText()).jsonObject }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun messageMatchesTheVectorBytes() {
        val message = vectors.getValue("message").jsonObject
        val bytes = BipeDeviceKey.message(message.text("user_id"), message.text("device_id"), message.text("challenge"))
        assertThat(bytes.toString(Charsets.UTF_8)).isEqualTo(message.text("message_utf8"))
        assertThat(BipeDeviceKey.MESSAGE_TAG).isEqualTo(vectors.text("tag"))
    }

    /** Java's SHA256withECDSA, the algorithm the Keystore key signs with, checks each vector as the server does. */
    @Test
    fun signatureVectorsVerifyAsExpected() {
        for (element in vectors.getValue("vectors").jsonArray) {
            val vector = element.jsonObject
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(vector.text("public_key"))))
            val message = BipeDeviceKey.message(vector.text("user_id"), vector.text("device_id"), vector.text("challenge"))
            val valid = runCatching {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(message)
                    verify(Base64.getDecoder().decode(vector.text("signature")))
                }
            }.getOrDefault(false)
            assertWithMessage(vector.text("name")).that(valid).isEqualTo(vector.getValue("expect").jsonPrimitive.boolean)
        }
    }

    @Test
    fun aliasNamesTheAccountWithSafeCharacters() {
        val alias = BipeDeviceKey.alias("@fmtestuser01:bipe.net.br")
        assertThat(alias).matches("bipe_device_key_[0-9a-f]{64}")
        assertThat(alias).isEqualTo(BipeDeviceKey.alias("@fmtestuser01:bipe.net.br"))
        assertThat(alias).isNotEqualTo(BipeDeviceKey.alias("@other:bipe.net.br"))
    }

    private companion object {
        /** Same lookup as SharedVectorTest: bipe-shared next to bipe-app, or BIPE_SHARED. */
        val vectorsDir: File = File(System.getenv("BIPE_SHARED") ?: "../../../../bipe-shared", "test-vectors")
    }
}
