// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.safety

import chat.bipe.core.ScamKind
import chat.bipe.shared.generated.BipeRegistries
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SafetyBoundaryTest {
    @Test fun ownAndUnreadableMessagesDoNotInvokeRust() {
        val unavailable = object : SafetyEngine {
            override fun warning(body: String): MessageSafety = throw AssertionError("Must not inspect this body")
        }
        assertEquals(MessageSafety.NotApplicable, messageSafety("own", true, true, unavailable))
        assertEquals(MessageSafety.NotApplicable, messageSafety("unreadable", false, false, unavailable))
    }

    @Test fun missingLibraryAndUnexpectedFailureAreNotNoWarning() {
        for (problem in listOf(UnsatisfiedLinkError("synthetic library failure"), IllegalStateException("synthetic-private-body"))) {
            val engine = object : SafetyEngine { override fun warning(body: String): MessageSafety { throw problem } }
            assertEquals(MessageSafety.Unavailable, messageSafety("synthetic", false, true, engine))
        }
    }

    @Test fun cancellationIsStillCancellation() {
        try {
            CoreSafety.boundary({ throw CancellationException() }, { MessageSafety.Unavailable })
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {}
    }

    @Test fun pauseIsOncePerMessageAndIndependentAcrossRoomsAndAccounts() {
        val first = MoneyPause()
        val second = MoneyPause()
        val start = 10_000L
        first.shown("same-event", ScamKind.PIX_KEY, start)
        val duration = BipeRegistries.SCAM_MONEY_PAUSE_SECONDS
        assertEquals(duration, first.remaining(start)["same-event"])
        assertNull(second.remaining(start)["same-event"])
        first.shown("same-event", ScamKind.PIX_KEY, start + 2_000)
        assertEquals(0, first.remaining(start + duration * 1_000L)["same-event"])
        first.shown("same-event", ScamKind.PIX_CODE, start + 100_000)
        assertEquals(0, first.remaining(start + 100_000)["same-event"])
        second.shown("same-event", ScamKind.PIX_KEY, start + 100_000)
        assertEquals(duration, second.remaining(start + 100_000)["same-event"])
    }

    @Test fun nonMoneyWarningsHaveNoPause() {
        val pause = MoneyPause()
        pause.shown("event", ScamKind.RECEIPT, 0)
        assertTrue(pause.remaining(0).isEmpty())
    }
}
