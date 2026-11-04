// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeAppLockTries
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockTriesTest {
    @Test
    fun firstTriesAreFree() {
        for (failures in 0 until BipeAppLockTries.FREE_TRIES) assertThat(BipeAppLockTries.wait(failures)).isNull()
    }

    @Test
    fun waitGrowsThenRepeats() {
        val free = BipeAppLockTries.FREE_TRIES
        assertThat(BipeAppLockTries.wait(free)).isEqualTo(30_000L)
        assertThat(BipeAppLockTries.wait(free + 1)).isEqualTo(60_000L)
        assertThat(BipeAppLockTries.wait(free + 4)).isEqualTo(3_600_000L)
        assertThat(BipeAppLockTries.wait(free + 40)).isEqualTo(3_600_000L)
    }

    /** Ten wrong PINs in a row never come quickly (bipe-chat/bipe#777): after the free tries, each one waits. */
    @Test
    fun tenWrongPinsTakeOverAnHour() {
        val total = (BipeAppLockTries.FREE_TRIES until 10).mapNotNull { BipeAppLockTries.wait(it) }.sum()
        assertThat(total).isAtLeast(3_600_000L)
    }

    @Test
    fun waitEndsAfterItsTime() {
        val free = BipeAppLockTries.FREE_TRIES
        assertThat(BipeAppLockTries.waitEnd(free, 1_000L, 1_000L)).isEqualTo(31_000L)
        assertThat(BipeAppLockTries.waitEnd(free, 1_000L, 30_999L)).isNotNull()
        assertThat(BipeAppLockTries.waitEnd(free, 1_000L, 31_000L)).isNull()
        assertThat(BipeAppLockTries.waitEnd(free - 1, 1_000L, 1_000L)).isNull()
    }
}
