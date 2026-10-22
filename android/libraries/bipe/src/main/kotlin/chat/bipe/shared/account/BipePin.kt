// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import androidx.annotation.StringRes
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.BipeStrings

/** The contract's `PinProblem` enum. Same ids as bipe-shared `pinProblem()`. */
enum class BipePinProblem(val id: String) {
    NOT_DIGITS("not_digits"),
    WRONG_LENGTH("wrong_length"),
    REPEATED_DIGIT("repeated_digit"),
    SEQUENCE("sequence"),
    DATE("date");

    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            NOT_DIGITS -> R.string.bipe_pin_problem_not_digits
            WRONG_LENGTH -> R.string.bipe_pin_problem_wrong_length
            REPEATED_DIGIT -> R.string.bipe_pin_problem_repeated_digit
            SEQUENCE -> R.string.bipe_pin_problem_sequence
            DATE -> R.string.bipe_pin_problem_date
        }

    /** Format arguments for [messageRes]. */
    val messageArgs: Array<Any>
        get() = if (this == WRONG_LENGTH) arrayOf(BipeRegistries.PIN_LENGTH.toString()) else emptyArray()

    fun localizedMessage(stringProvider: BipeStrings): String = stringProvider.getString(messageRes, *messageArgs)

    companion object {
        fun fromId(id: String): BipePinProblem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The PIN rule of ADR 0057 (bipe-shared `registries/accounts.json`, reference `pinProblem()` in `tools/lib/registries.mjs`).
 * Cases: `test-vectors/pins.json`. The server checks the same rule and is the authority.
 */
object BipePin {
    const val LENGTH = BipeRegistries.PIN_LENGTH

    /** `null` when [pin] is strong enough. Checks in the shared order: not_digits, wrong_length, repeated_digit, sequence, date. */
    fun problem(pin: String): BipePinProblem? {
        if (!pin.all { it in '0'..'9' }) return BipePinProblem.NOT_DIGITS
        if (pin.length != LENGTH) return BipePinProblem.WRONG_LENGTH
        val digits = pin.map { it - '0' }
        if (digits.all { it == digits[0] }) return BipePinProblem.REPEATED_DIGIT
        // Up (step 1) or down (step 9, which is minus 1 modulo 10), with wrap: 78901234 and 21098765 count.
        for (step in intArrayOf(1, 9)) {
            if ((1 until digits.size).all { i -> digits[i] == (digits[i - 1] + step) % 10 }) return BipePinProblem.SEQUENCE
        }
        fun number(from: Int, to: Int) = pin.substring(from, to).toInt()
        if (isDate(year = number(4, 8), month = number(2, 4), day = number(0, 2)) ||
            isDate(year = number(0, 4), month = number(4, 6), day = number(6, 8))
        ) {
            return BipePinProblem.DATE
        }
        return null
    }

    /** Keeps the digits a user typed or pasted, up to [LENGTH]. */
    fun sanitizeInput(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)

    private fun isDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in BipeRegistries.PIN_DATE_YEAR_MIN..BipeRegistries.PIN_DATE_YEAR_MAX || month !in 1..12 || day < 1) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        return day <= days
    }
}
