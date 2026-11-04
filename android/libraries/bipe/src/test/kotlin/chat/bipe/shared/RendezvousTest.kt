// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.weblink.BipeWebLink
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Link a computer accepts only codes whose rendezvous is on the account's homeserver (bipe-chat/bipe#785). */
class RendezvousTest {
    /** An MSC4108 login code: MATRIX, version 0x02, intent 0x03, a 32-byte key, the rendezvous URL. */
    private fun msc4108(url: String, version: Byte = 0x02): ByteArray {
        val text = url.encodeToByteArray()
        return "MATRIX".encodeToByteArray() + byteArrayOf(version, 0x03) + ByteArray(32) { 7 } +
            byteArrayOf((text.size shr 8).toByte(), (text.size and 0xff).toByte()) + text
    }

    private val homeserver = "https://matrix.bipe.net.br/"

    @Test
    fun acceptsOwnHomeserver() {
        val code = msc4108("https://matrix.bipe.net.br/_synapse/client/rendezvous/abc")
        assertThat(BipeWebLink.rendezvousUrl(code, null)?.host).isEqualTo("matrix.bipe.net.br")
        assertThat(BipeWebLink.isOwnRendezvous(code, null, homeserver)).isTrue()
    }

    @Test
    fun refusesOtherServer() {
        assertThat(BipeWebLink.isOwnRendezvous(msc4108("https://matrix-client.matrix.org/_synapse/client/rendezvous/x"), null, homeserver)).isFalse()
        // A suffix is not the same host.
        assertThat(BipeWebLink.isOwnRendezvous(msc4108("https://evilmatrix.bipe.net.br.example.com/r"), null, homeserver)).isFalse()
        assertThat(BipeWebLink.isOwnRendezvous(msc4108("http://matrix.bipe.net.br/r"), null, homeserver)).isFalse()
    }

    @Test
    fun refusesBrokenCodes() {
        assertThat(BipeWebLink.isOwnRendezvous(msc4108("https://matrix.bipe.net.br/r", version = 0x01), null, homeserver)).isFalse()
        assertThat(BipeWebLink.isOwnRendezvous(msc4108("https://matrix.bipe.net.br/r").copyOf(50), null, homeserver)).isFalse()
        assertThat(BipeWebLink.isOwnRendezvous(byteArrayOf(1, 2, 3), null, homeserver)).isFalse()
    }

    @Test
    fun usesBaseUrlOfOtherCodes() {
        assertThat(BipeWebLink.isOwnRendezvous(byteArrayOf(1, 2, 3), "https://matrix.bipe.net.br", homeserver)).isTrue()
        assertThat(BipeWebLink.isOwnRendezvous(byteArrayOf(1, 2, 3), "https://matrix.org", homeserver)).isFalse()
    }
}
