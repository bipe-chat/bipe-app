// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.onboarding

import android.content.Context
import chat.bipe.shared.generated.BipeOnboarding

/**
 * The PIN reminder schedule (registries/onboarding.json pinReminder, bipe-chat/bipe#202). Port of `pinReminderNext()`
 * in bipe-shared tools/lib/registries.mjs; must match test-vectors/onboarding-schedule.json. The iOS counterpart is
 * `BipeOnboardingSchedule` in BipeKit.
 */
object BipeOnboardingSchedule {
    enum class PinResult(val id: String) { RIGHT("right"), WRONG("wrong"), LATER("later") }

    data class Next(val step: Int, val gapDays: Int)

    /** The PIN reminder after a check: the next step and the days until the next reminder. */
    fun pinReminderNext(step: Int, result: PinResult): Next {
        if (result == PinResult.LATER) return Next(step, 1)
        val next = if (result == PinResult.RIGHT) step + 1 else 0
        return Next(next, gapDays(next))
    }

    /** The gap before the first reminder is `gapDays(0)`. */
    fun gapDays(after: Int): Int =
        BipeOnboarding.PIN_REMINDER_DAYS.getOrNull(after) ?: BipeOnboarding.PIN_REMINDER_THEN_EVERY_DAYS
}

/** What the PIN reminder remembers per account on this phone. Holds no secret: never the PIN. */
class BipePinReminderMemory(context: Context, userId: String) {
    private val prefs = context.getSharedPreferences("bipe_pin_reminder", Context.MODE_PRIVATE)
    private val firstSeenKey = "first_seen_$userId"
    private val stepKey = "step_$userId"
    private val nextAtKey = "next_at_$userId"

    /** When this phone first saw the account; set on the first read. */
    val firstSeen: Long
        get() = prefs.getLong(firstSeenKey, 0L).takeIf { it > 0L }
            ?: System.currentTimeMillis().also { prefs.edit().putLong(firstSeenKey, it).apply() }

    val step: Int get() = prefs.getInt(stepKey, 0)

    /** When the next reminder is due. The first one comes `gapDays(0)` after this phone first saw the account. */
    val nextAt: Long
        get() = prefs.getLong(nextAtKey, 0L).takeIf { it > 0L } ?: (firstSeen + BipeOnboardingSchedule.gapDays(0) * DAY)

    fun isDue(now: Long = System.currentTimeMillis()): Boolean = now >= nextAt

    fun checked(result: BipeOnboardingSchedule.PinResult, now: Long = System.currentTimeMillis()) {
        val next = BipeOnboardingSchedule.pinReminderNext(step, result)
        prefs.edit().putInt(stepKey, next.step).putLong(nextAtKey, now + next.gapDays * DAY).apply()
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
