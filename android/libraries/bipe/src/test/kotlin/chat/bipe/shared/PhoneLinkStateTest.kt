// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeMyPhone
import chat.bipe.shared.account.BipePhoneLinkState
import chat.bipe.shared.generated.BipeRegistries
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec phone-link-status (bipe-chat/bipe#470): the thresholds come from registries/phone-numbers.json. */
class PhoneLinkStateTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)

    private fun phone(days: Long?, linked: Boolean = true) =
        BipeMyPhone(linked = linked, discoverableByPhone = true, linkedAt = now, confirmBy = days?.let { now.plus(Duration.ofDays(it)) })

    @Test
    fun states() {
        val warn = BipeRegistries.PHONE_LINK_CONFIRM_WARN_DAYS.toLong()
        assertEquals(BipePhoneLinkState.NotLinked, BipePhoneLinkState.of(phone(null, linked = false), now))
        assertEquals(BipePhoneLinkState.Linked(now.plus(Duration.ofDays(warn + 1))), BipePhoneLinkState.of(phone(warn + 1), now))
        assertEquals(BipePhoneLinkState.ConfirmSoon(now.plus(Duration.ofDays(warn))), BipePhoneLinkState.of(phone(warn), now))
        assertEquals(BipePhoneLinkState.Expired(now), BipePhoneLinkState.of(phone(0), now))
        assertTrue(BipePhoneLinkState.of(phone(-10), now).needsAttention)
        assertFalse(BipePhoneLinkState.of(phone(200), now).needsAttention)
    }
}
