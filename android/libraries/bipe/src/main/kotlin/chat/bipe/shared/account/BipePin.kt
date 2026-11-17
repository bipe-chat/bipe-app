// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.account

import android.content.Context
import chat.bipe.core.PinProblem
import chat.bipe.core.pinProblem
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
    fun problem(pin: String): BipePinProblem? = when (pinProblem(pin)) {
        PinProblem.NOT_DIGITS -> BipePinProblem.NOT_DIGITS
        PinProblem.WRONG_LENGTH -> BipePinProblem.WRONG_LENGTH
        PinProblem.REPEATED_DIGIT -> BipePinProblem.REPEATED_DIGIT
        PinProblem.SEQUENCE -> BipePinProblem.SEQUENCE
        PinProblem.DATE -> BipePinProblem.DATE
        null -> null
    }

    /** Keeps the digits only, at most the PIN length. Paste is allowed. */
    fun sanitize(input: String): String = input.filter { it in '0'..'9' }.take(BipeRegistries.PIN_LENGTH)
}
