// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeRegistries

/** The contract's `PinProblem`. Same ids as bipe-shared `pinProblem()`. */
enum class BipePinProblem(val id: String) {
    NOT_DIGITS("not_digits"),
    WRONG_LENGTH("wrong_length"),
    REPEATED_DIGIT("repeated_digit"),
    SEQUENCE("sequence"),
    DATE("date"),
    ;

    fun localizedMessage(context: Context): String = when (this) {
        NOT_DIGITS -> context.getString(R.string.bipe_pin_problem_not_digits)
        WRONG_LENGTH -> context.getString(R.string.bipe_pin_problem_wrong_length, BipeRegistries.PIN_LENGTH.toString())
        REPEATED_DIGIT -> context.getString(R.string.bipe_pin_problem_repeated_digit)
        SEQUENCE -> context.getString(R.string.bipe_pin_problem_sequence)
        DATE -> context.getString(R.string.bipe_pin_problem_date)
    }

    companion object {
        fun fromId(id: String): BipePinProblem? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The 8-digit PIN of a device-first account (ADR 0057). Rule and order: bipe-shared registries/accounts.json and
 * `pinProblem()` in tools/lib/registries.mjs; cases in test-vectors/pins.json. The server checks again and decides.
 */
object BipePin {
    /** `null` when the PIN is strong enough. */
    fun problem(pin: String): BipePinProblem? {
        if (!pin.all { it in '0'..'9' }) return BipePinProblem.NOT_DIGITS
        if (pin.length != BipeRegistries.PIN_LENGTH) return BipePinProblem.WRONG_LENGTH
        val digits = pin.map { it - '0' }
        if (digits.all { it == digits[0] }) return BipePinProblem.REPEATED_DIGIT
        // Up or down by one, with wrap from 9 to 0 and back.
        for (step in listOf(1, 9)) {
            if ((1 until digits.size).all { digits[it] == (digits[it - 1] + step) % 10 }) return BipePinProblem.SEQUENCE
        }
        fun number(from: Int, to: Int) = digits.subList(from, to).fold(0) { acc, d -> acc * 10 + d }
        if (isDate(year = number(4, 8), month = number(2, 4), day = number(0, 2)) ||
            isDate(year = number(0, 4), month = number(4, 6), day = number(6, 8))
        ) {
            return BipePinProblem.DATE
        }
        return null
    }

    /** Keeps the digits only, at most the PIN length. Paste is allowed. */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }.take(BipeRegistries.PIN_LENGTH)

    private fun isDate(year: Int, month: Int, day: Int): Boolean {
        if (year !in BipeRegistries.PIN_DATE_YEAR_MIN..BipeRegistries.PIN_DATE_YEAR_MAX || month !in 1..12 || day < 1) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[month - 1]
        return day <= days
    }
}
