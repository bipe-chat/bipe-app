// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.displayPhone
import chat.bipe.core.normalizePhone

/**
 * Phone numbers for contact discovery: Brazilian mobiles in any local format, other countries only when written with `+` or `00`.
 * Rule: bipe-shared/registries/phone-numbers.json (generated); cases: test-vectors/phone-numbers.json. Port of BipePhoneNumber on iOS,
 * same result as the API's `normalizePhone`.
 */
object BipePhoneNumber {
    /** E.164 (`+5511987654321`) from an address-book entry, or `null` (bipe-core `normalizePhone`). */
    fun normalize(text: String): String? = normalizePhone(text)

    /** An E.164 number formatted for reading (ADR 0191), from bipe-core `displayPhone`. Other text comes back unchanged. */
    fun display(e164: String): String = displayPhone(e164)

    /** A number as saved in the address book, for reading: formatted when it normalises, else as saved. */
    fun displaySaved(text: String): String = normalize(text)?.let(::display) ?: text.trim()
}
