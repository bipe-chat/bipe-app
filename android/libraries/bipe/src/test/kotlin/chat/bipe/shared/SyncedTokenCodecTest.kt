// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeSyncedToken
import chat.bipe.shared.account.BipeSyncedTokenCodec
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Test

/** The Block Store entry format, which must stay the fork's (bipe-android libraries/bipeblockstore). */
class SyncedTokenCodecTest {
    private val aliceKey = "chat.bipe.recovery_token." +
        MessageDigest.getInstance("SHA-256").digest(ALICE.encodeToByteArray()).take(16).joinToString("") { "%02x".format(it) }

    @Test
    fun writesTheForkFormat() {
        assertThat(BipeSyncedTokenCodec.key(ALICE)).isEqualTo(aliceKey)
        assertThat(BipeSyncedTokenCodec.key(null)).isEqualTo("chat.bipe.recovery_token")
        assertThat(BipeSyncedTokenCodec.encode(ALICE, "token-a").decodeToString()).isEqualTo("$ALICE\ntoken-a")
    }

    @Test
    fun readsOnlyEntriesThatBelongToTheirKey() {
        val cases = mapOf(
            (aliceKey to "$ALICE\ntoken-a") to BipeSyncedToken(ALICE, "token-a"),
            ("chat.bipe.recovery_token" to "bare") to BipeSyncedToken(null, "bare"),
            (BipeSyncedTokenCodec.key(BOB) to "$ALICE\ntoken-a") to null,
            ("someone.else" to "$ALICE\ntoken-a") to null,
            (aliceKey to "no line break") to null,
            (aliceKey to "$ALICE\n") to null,
            ("chat.bipe.recovery_token" to "") to null,
        )
        for ((input, expected) in cases) {
            assertThat(BipeSyncedTokenCodec.decode(input.first, input.second.encodeToByteArray())).isEqualTo(expected)
        }
    }

    @Test
    fun refusesWhatBlockStoreOrTheFormatCannotHold() {
        val tooLong = runCatching { BipeSyncedTokenCodec.encode(ALICE, "x".repeat(BipeSyncedTokenCodec.MAX_BYTES)) }
        val lineBreak = runCatching { BipeSyncedTokenCodec.encode("@a\nb:bipe.net.br", "t") }
        assertThat(tooLong.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(lineBreak.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(BipeSyncedToken(ALICE, "token-a").toString()).doesNotContain("token-a")
    }
}
