// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared.company

import chat.bipe.shared.BadgePayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The CVM lines of the company seal detail (ADR 0077 point 3), from the signed `cvm` field of a cnpj badge
 * (bipe-shared docs/badge-attestation.md). Same rule as bipe-shared tools/lib/cvm.mjs and test-vectors/cvm-lines.json.
 * Fail closed: a missing or bad field shows nothing.
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

    private val DATE = Regex("(\\d{4})-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])")

    /** YYYY-MM-DD to DD/MM/AAAA, else null. */
    internal fun displayDate(value: JsonElement?): String? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val m = DATE.matchEntire(text) ?: return null
        return "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}"
    }

    /** The CVM lines of a verified badge, or none. The app module does not see the JSON types, so it calls this. */
    fun forBadge(payload: BadgePayload?): Result = lines(payload?.cvm)

    fun lines(field: JsonElement?): Result {
        val obj = field as? JsonObject ?: return Result.NONE
        val st = (obj["st"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (st) {
            "stop_order" -> displayDate(obj["on"])?.let { Result(listOf(Line(Key.STOP_ORDER, it)), true) } ?: Result.NONE
            "registered" -> {
                val cat = obj["cat"] as? JsonArray ?: return Result.NONE
                val names = cat.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (names.isEmpty() || names.size > 10 || names.any { it == null || it.isBlank() || it.length > 120 }) return Result.NONE
                Result(names.map { it!!.trim() }.distinct().map { Line(Key.REGISTERED, it) }, false)
            }
            "cancelled" -> displayDate(obj["on"])?.let { Result(listOf(Line(Key.CANCELLED, it)), false) } ?: Result.NONE
            "no_registration" -> Result(listOf(Line(Key.NO_REGISTRATION), Line(Key.BANK_NOTE)), false)
            else -> Result.NONE
        }
    }
}
