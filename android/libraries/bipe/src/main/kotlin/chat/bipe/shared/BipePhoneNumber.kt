// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries

/**
 * Phone numbers for contact discovery: Brazilian mobiles in any local format, other countries only when written with `+` or `00`.
 * Rule: bipe-shared/registries/phone-numbers.json (generated); cases: test-vectors/phone-numbers.json. Port of BipePhoneNumber on iOS,
 * same result as the API's `normalizePhone`.
 */
object BipePhoneNumber {
    /** E.164 (`+5511987654321`) from an address-book entry, or `null`. */
    fun normalize(text: String): String? {
        val head = text.take(BipeRegistries.PHONE_MAX_INPUT_LENGTH).replace("(0)", "").trim()
        var plus = head.filter { it in '0'..'9' || it == '+' }.startsWith("+")
        var digits = head.filter { it in '0'..'9' }
        if (!plus && digits.startsWith("00")) {
            plus = true
            digits = digits.drop(2)
        }
        val countryCode = BipeRegistries.PHONE_COUNTRY_CODE
        val national = BipeRegistries.PHONE_MOBILE_LENGTH + 2
        if (plus) {
            if (!digits.startsWith(countryCode)) {
                // Another country: E.164 as written, length checked only.
                val valid = digits.length in BipeRegistries.PHONE_INTERNATIONAL_MIN_DIGITS..BipeRegistries.PHONE_INTERNATIONAL_MAX_DIGITS &&
                    !digits.startsWith("0")
                return if (valid) "+$digits" else null
            }
            digits = digits.drop(countryCode.length)
        } else if (digits.length == national + 3 && digits.startsWith("0")) {
            digits = digits.drop(3) // trunk 0 + carrier code
        } else if (digits.length == national + 1 && digits.startsWith("0")) {
            digits = digits.drop(1) // trunk 0
        } else if (digits.length == national + countryCode.length && digits.startsWith(countryCode)) {
            digits = digits.drop(countryCode.length)
        }
        if (digits.length != national) return null
        val area = digits.take(2)
        val subscriber = digits.drop(2)
        if (area !in BipeRegistries.phoneAreaCodes || !subscriber.startsWith(BipeRegistries.PHONE_MOBILE_PREFIX)) return null
        return "+$countryCode$area$subscriber"
    }
}
