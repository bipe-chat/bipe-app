// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeForkVaultCipher
import chat.bipe.shared.account.BipeLegacyShape
import com.google.common.truth.Truth.assertThat
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Test

/** Synthetic vaults under a test AES key only (bipe-chat/bipe#603). No Keystore, no real file. */
class LegacyDataTest {
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vaultJson = """{"accounts":{"@ana:example.test":{"password":"fixture-pass"},"@bia:example.test":{"local_token":"fixture-token"}},"pending_inviters":{"@bia:example.test":"@ana:example.test"}}"""

    private fun seal(plain: String, layout: BipeForkVaultCipher.Layout): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val body = cipher.doFinal(plain.encodeToByteArray())
        val bytes = when (layout) {
            BipeForkVaultCipher.Layout.IV12_PREFIX -> iv + body
            BipeForkVaultCipher.Layout.IV_LENGTH_BYTE -> byteArrayOf(12) + iv + body
            BipeForkVaultCipher.Layout.IV_LENGTH_INT -> byteArrayOf(0, 0, 0, 12) + iv + body
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private val open: (BipeForkVaultCipher.Sealed) -> ByteArray? = { sealed ->
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
            doFinal(sealed.body)
        }
    }

    @Test
    fun probeNamesTheLayoutThatAuthenticatesAndShowsNoValue() {
        for (layout in BipeForkVaultCipher.Layout.entries) {
            val probe = BipeForkVaultCipher.probe(seal(vaultJson, layout), open)
            assertThat(probe.layout).isEqualTo(layout)
            assertThat(probe.accounts).isEqualTo(2)
            assertThat(probe.pendingInviters).isEqualTo(1)
            val shown = probe.toString()
            for (secret in listOf("fixture-pass", "fixture-token", "@ana", "@bia")) assertThat(shown).doesNotContain(secret)
            assertThat(probe.shape).contains("local_token")
        }
    }

    @Test
    fun noConfirmedLayoutMeansNoImport() {
        assertThat(BipeForkVaultCipher.CONFIRMED).isNull()
        val text = seal(vaultJson, BipeForkVaultCipher.Layout.IV12_PREFIX)
        assertThat(BipeForkVaultCipher.read(text, open)).isNull()
        val data = BipeForkVaultCipher.read(text, open, BipeForkVaultCipher.Layout.IV12_PREFIX)
        assertThat(data?.accounts?.keys).containsExactly("@ana:example.test", "@bia:example.test")
    }

    @Test
    fun wrongKeyOrNotBase64GivesNoLayout() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val wrong: (BipeForkVaultCipher.Sealed) -> ByteArray? = { sealed ->
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, other, GCMParameterSpec(128, sealed.iv))
                doFinal(sealed.body)
            }
        }
        assertThat(BipeForkVaultCipher.probe(seal(vaultJson, BipeForkVaultCipher.Layout.IV12_PREFIX), wrong).layout).isNull()
        val notBase64 = BipeForkVaultCipher.probe("{not base64}", open)
        assertThat(notBase64.base64).isFalse()
        assertThat(notBase64.layout).isNull()
    }

    @Test
    fun redactKeepsNamesAndHidesValues() {
        assertThat(BipeLegacyShape.redact("bipe-account.fork")).isEqualTo("bipe-account.fork")
        assertThat(BipeLegacyShape.redact("@ana:example.test")).isEqualTo("@…:example.test")
        assertThat(BipeLegacyShape.redact("ana@example.test")).isEqualTo("text(16)")
        assertThat(BipeLegacyShape.redact("9f86d081884c7d659a2feaa0c55ad015")).isEqualTo("text(32)")
        val shape = BipeLegacyShape.describe("""{"token":"fixture","n":2,"ok":true,"list":[{"a":null}]}""").toString()
        assertThat(shape).doesNotContain("fixture")
        assertThat(shape).contains("string(7)")
        assertThat(shape).contains("bool")
    }
}
