// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.account.BipeRecoveryReminder
import chat.bipe.shared.generated.BipeRegistries
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.time.Instant
import org.junit.Test

class RecoveryReminderTest {
    private val start = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun waitsFirstAfterDaysThenShows() {
        val first = start.plus(Duration.ofDays(BipeRegistries.RECOVERY_REMINDER_FIRST_AFTER_DAYS.toLong()))
        assertThat(BipeRecoveryReminder.isDue(first.minusSeconds(1), start, null)).isFalse()
        assertThat(BipeRecoveryReminder.isDue(first, start, null)).isTrue()
    }

    @Test
    fun snoozesFollowTheListAndTheLastRepeats() {
        val days = BipeRegistries.RECOVERY_REMINDER_SNOOZE_DAYS
        (days + days.last() + days.last()).forEachIndexed { count, expected ->
            assertThat(BipeRecoveryReminder.snoozedUntil(start, count)).isEqualTo(start.plus(Duration.ofDays(expected.toLong())))
        }
        val until = BipeRecoveryReminder.snoozedUntil(start, 0)
        assertThat(BipeRecoveryReminder.isDue(until.minusSeconds(1), Instant.EPOCH, until)).isFalse()
        assertThat(BipeRecoveryReminder.isDue(until, Instant.EPOCH, until)).isTrue()
    }
}
