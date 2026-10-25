// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeWorkHours
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Work side opening hours (bipe-chat/bipe#511). The same cases as bipe-shared test-vectors/work-hours.json. */
class WorkHoursTest {
    private fun hours(zone: String, vararg days: BipeWorkHours.Day) = BipeWorkHours(true, zone, days.toList())
    private fun day(day: Int, vararg ranges: Pair<String, String>) = BipeWorkHours.Day(day, ranges.map { BipeWorkHours.Range(it.first, it.second) })

    @Test
    fun vectors() {
        val monday = day(1, "08:00" to "18:00")
        val sundayNight = day(7, "20:00" to "24:00")
        val lunch = day(3, "08:00" to "12:00", "13:00" to "18:00")
        val cases = listOf(
            Triple(hours("America/Sao_Paulo", monday), "2026-10-26T13:00:00Z", true),
            Triple(hours("America/Sao_Paulo", monday), "2026-10-26T11:00:00Z", true),
            Triple(hours("America/Sao_Paulo", monday), "2026-10-26T21:00:00Z", false),
            Triple(hours("America/Sao_Paulo", monday), "2026-10-26T20:59:00Z", true),
            Triple(hours("America/Sao_Paulo", monday), "2026-10-26T10:59:00Z", false),
            Triple(hours("America/Sao_Paulo", monday), "2026-10-25T14:00:00Z", false),
            Triple(hours("America/Sao_Paulo", sundayNight), "2026-10-26T01:30:00Z", true),
            Triple(hours("America/Sao_Paulo", sundayNight), "2026-10-26T02:59:00Z", true),
            Triple(hours("America/Sao_Paulo", lunch), "2026-10-28T15:30:00Z", false),
            Triple(hours("America/Sao_Paulo", lunch), "2026-10-28T16:00:00Z", true),
            Triple(hours("America/Manaus", monday), "2026-10-26T11:30:00Z", false),
            Triple(hours("America/Manaus", monday), "2026-10-26T12:00:00Z", true),
            Triple(hours("America/Sao_Paulo"), "2026-10-26T12:00:00Z", false),
        )
        for ((value, at, open) in cases) assertEquals(at, open, value.isOpen(Instant.parse(at)))
    }

    @Test
    fun quietOnlyWhenEnabled() {
        val closed = hours("America/Sao_Paulo")
        assertTrue(closed.isQuiet(Instant.now()))
        assertFalse(closed.copy(enabled = false).isQuiet(Instant.now()))
        assertEquals(closed, BipeWorkHours.decoded(closed.encoded()))
    }
}
