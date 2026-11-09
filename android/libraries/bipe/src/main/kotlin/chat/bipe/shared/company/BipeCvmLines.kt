// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import chat.bipe.core.CvmLineKey
import chat.bipe.core.cvmLines
import chat.bipe.shared.BadgePayload
import kotlinx.serialization.json.JsonElement

/**
 * The CVM lines of the company seal detail (ADR 0077 point 3), from the signed `cvm` field of a cnpj badge
 * (bipe-shared docs/badge-attestation.md). The rule runs in bipe-core (`cvmLines`, ADR 0267) and matches bipe-shared
 * tools/lib/cvm.mjs and test-vectors/cvm-lines.json. Fail closed: a missing or bad field shows nothing.
 */
object BipeCvmLines {
    enum class Key(val key: String) {
        REGISTERED("bipe_cvm_registered"),
        CANCELLED("bipe_cvm_cancelled"),
        NO_REGISTRATION("bipe_cvm_no_registration"),
        BANK_NOTE("bipe_cvm_bank_note"),
        STOP_ORDER("bipe_cvm_stop_order"),
    }

    data class Line(val key: Key, val arg: String? = null)

    /** [danger]: a stop order; the line is red and chats from the account show bipe_cvm_stop_order_card. */
    data class Result(val lines: List<Line>, val danger: Boolean) {
        companion object {
            val NONE = Result(emptyList(), false)
        }
    }

    /** The CVM lines of a verified badge, or none. The app module does not see the JSON types, so it calls this. */
    fun forBadge(payload: BadgePayload?): Result = lines(payload?.cvm)

    fun lines(field: JsonElement?): Result {
        val result = cvmLines(field?.toString())
        return Result(result.lines.map { Line(key(it.key), it.arg) }, result.danger)
    }

    private fun key(key: CvmLineKey): Key = when (key) {
        CvmLineKey.REGISTERED -> Key.REGISTERED
        CvmLineKey.CANCELLED -> Key.CANCELLED
        CvmLineKey.NO_REGISTRATION -> Key.NO_REGISTRATION
        CvmLineKey.BANK_NOTE -> Key.BANK_NOTE
        CvmLineKey.STOP_ORDER -> Key.STOP_ORDER
    }
}
