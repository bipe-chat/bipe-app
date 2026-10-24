// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Spec scam-warning, money pause. */
class MoneyPauseTest {
    private val start = 1_000_000L

    @Test
    fun pixCodeStartsAFiveSecondPause() {
        val pause = BipeMoneyPause().started("\$e", ScamWarningKind.PIX_CODE, start)
        assertThat(pause.secondsLeft("\$e", start)).isEqualTo(5)
        assertThat(pause.secondsLeft("\$e", start + 4_200)).isEqualTo(1)
        assertThat(pause.secondsLeft("\$e", start + 5_000)).isEqualTo(0)
    }

    @Test
    fun runsOncePerMessage() {
        val pause = BipeMoneyPause().started("\$e", ScamWarningKind.BANK_ALERT, start)
        val again = pause.started("\$e", ScamWarningKind.BANK_ALERT, start + 10_000)
        assertThat(again).isEqualTo(pause)
        assertThat(again.secondsLeft("\$e", start + 10_000)).isEqualTo(0)
    }

    @Test
    fun kindsWithoutMoneyGetNoPause() {
        for (kind in listOf(ScamWarningKind.RECEIPT, ScamWarningKind.NEW_NUMBER, ScamWarningKind.URGENCY, ScamWarningKind.PIN_REQUEST)) {
            assertThat(BipeMoneyPause().started(kind.id, kind, start).secondsLeft(kind.id, start)).isEqualTo(0)
        }
        assertThat(BipeMoneyPause.applies(ScamWarningKind.LOOKALIKE_LINK)).isTrue()
    }
}
