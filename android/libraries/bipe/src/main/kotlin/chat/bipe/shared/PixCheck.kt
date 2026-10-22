// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries
import java.util.regex.Pattern

/**
 * "Conferir no Bipe" phase 1 (bipe ADR 0091): the Pix code and the Pix keys in text that the user
 * shared from another app. Runs on the device only. Must match bipe-shared/test-vectors/pix-check.json.
 * iOS: `PixCheck` in BipeKit.
 */
data class PixCheck(
    val code: Code?,
    val keys: List<Key>,
    /** The rule 9 warning of the text around the code, never PIX_CODE or PIX_KEY. */
    val warning: ScamWarningKind?,
) {
    enum class Status(val id: String) { VALID("valid"), BAD_CRC("badCrc"), MALFORMED("malformed") }

    enum class KeyType(val id: String) {
        CPF("cpf"),
        CNPJ("cnpj"),
        PHONE("phone"),
        EMAIL("email"),
        RANDOM("random"),
        UNKNOWN("unknown");

        companion object {
            fun fromId(id: String): KeyType? = entries.firstOrNull { it.id == id }
        }
    }

    /** A BR Code. Every field except [status] is null or false when the code is malformed. */
    data class Code(
        val status: Status,
        val dynamic: Boolean = false,
        val singleUse: Boolean = false,
        val key: String? = null,
        val keyType: KeyType? = null,
        /** The payload location of a dynamic code, without a scheme. */
        val url: String? = null,
        val name: String? = null,
        val city: String? = null,
        /** As written in the code, for example "50.00". */
        val amount: String? = null,
        val txid: String? = null,
        val info: String? = null,
    ) {
        val urlHost: String? get() = url?.substringBefore('/')
    }

    /** A Pix key in the text. [types] lists every key pattern that matched the same span, in registry order. */
    data class Key(val value: String, val types: List<KeyType>)

    val foundPix: Boolean get() = code != null || keys.isNotEmpty()

    companion object {
        /** The screen itself explains Pix, so these warnings are not repeated. */
        private val skippedWarnings = setOf(ScamWarningKind.PIX_CODE, ScamWarningKind.PIX_KEY)
        private const val PIX_GUI = "br.gov.bcb.pix"

        fun check(text: String): PixCheck {
            val points = text.codePoints().toArray().map { String(Character.toChars(it)) }
            val found = findBrCode(points)
            val rest = found?.let { points.subList(0, it.start).joinToString("") + " " + points.subList(it.end, points.size).joinToString("") } ?: text
            return PixCheck(
                code = found?.code,
                keys = keys(rest),
                warning = ScamDetector.warningKind(ScamDetector.matchIds(rest), rest, skipping = skippedWarnings),
            )
        }

        fun keyType(key: String): KeyType = when {
            key.contains('@') -> KeyType.EMAIL
            key.matches(Regex("\\+[0-9]{12,13}")) -> KeyType.PHONE
            key.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) -> KeyType.RANDOM
            key.matches(Regex("[0-9]{11}")) -> KeyType.CPF
            key.matches(Regex("[0-9A-Z]{12}[0-9]{2}")) -> KeyType.CNPJ
            else -> KeyType.UNKNOWN
        }

        /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) over the UTF-8 bytes, as 4 uppercase hex digits. */
        internal fun crc16(text: String): String {
            var crc = 0xFFFF
            for (byte in text.toByteArray(Charsets.UTF_8)) {
                crc = crc xor (byte.toInt() and 0xFF shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) crc shl 1 xor 0x1021 and 0xFFFF else crc shl 1 and 0xFFFF
                }
            }
            return "%04X".format(crc)
        }

        private data class Field(val id: String, val value: String, val start: Int, val end: Int)

        private data class Found(val start: Int, val end: Int, val code: Code)

        /** EMV TLV: two-digit ID, two-digit length, value. Lengths count code points. Null when the shape is wrong. */
        private fun readTlv(points: List<String>, from: Int, until: Int): List<Field>? {
            val fields = mutableListOf<Field>()
            var index = from
            while (index < until) {
                if (index + 4 > until) return null
                val head = points.subList(index, index + 4).joinToString("")
                if (!head.matches(Regex("[0-9]{4}"))) return null
                val id = head.substring(0, 2)
                val length = head.substring(2).toInt()
                if (index + 4 + length > until) return null
                fields += Field(id, points.subList(index + 4, index + 4 + length).joinToString(""), index, index + 4 + length)
                index += 4 + length
                if (id == "63") break
            }
            return fields
        }

        private fun subfields(value: String): Map<String, String>? {
            val points = value.codePoints().toArray().map { String(Character.toChars(it)) }
            return readTlv(points, 0, points.size)?.associate { it.id to it.value }
        }

        private fun findBrCode(points: List<String>): Found? {
            for (start in 0..points.size - 6) {
                if (points.subList(start, start + 6).joinToString("") != "000201") continue
                if (!asciiLower(points.subList(start, points.size).joinToString("")).contains(PIX_GUI)) return null
                val fields = readTlv(points, start, points.size) ?: return Found(start, points.size, Code(Status.MALFORMED))
                return Found(start, fields.last().end, parse(points, start, fields))
            }
            return null
        }

        private fun parse(points: List<String>, start: Int, fields: List<Field>): Code {
            val malformed = Code(Status.MALFORMED)
            val crcField = fields.last()
            if (fields.first().id != "00" || fields.first().value != "01") return malformed
            if (crcField.id != "63" || crcField.value.codePointCount(0, crcField.value.length) != 4) return malformed
            val byId = fields.associate { it.id to it.value }

            val account = fields.firstNotNullOfOrNull { field ->
                if (field.id.toInt() !in 26..51) return@firstNotNullOfOrNull null
                subfields(field.value)?.takeIf { asciiLower(it["00"] ?: "") == PIX_GUI }
            } ?: return malformed
            val key = account["01"]?.trim()
            val url = account["25"]
            if (key == null && url == null) return malformed

            var txid: String? = null
            byId["62"]?.let { extra ->
                val subs = subfields(extra) ?: return malformed
                txid = subs["05"]?.takeIf { it != "***" }
            }

            val signed = points.subList(start, crcField.start + 4).joinToString("")
            return Code(
                status = if (crc16(signed) == crcField.value.uppercase()) Status.VALID else Status.BAD_CRC,
                dynamic = url != null,
                singleUse = byId["01"] == "12",
                key = key,
                keyType = key?.let(::keyType),
                url = url,
                name = byId["59"],
                city = byId["60"],
                amount = byId["54"],
                txid = txid,
                info = account["02"],
            )
        }

        private val keyExpressions: List<Pair<KeyType, Pattern>> by lazy {
            BipeRegistries.pixKeyPatterns.mapNotNull { pattern ->
                val type = KeyType.fromId(pattern.id) ?: return@mapNotNull null
                runCatchingExceptions { Pattern.compile(pattern.regex, Pattern.UNICODE_CHARACTER_CLASS) }.getOrNull()?.let { type to it }
            }
        }

        private fun keys(text: String): List<Key> {
            val spans = LinkedHashMap<Pair<Int, Int>, MutableList<KeyType>>()
            for ((type, expression) in keyExpressions) {
                val matcher = expression.matcher(text)
                while (matcher.find()) {
                    spans.getOrPut(matcher.start() to matcher.end()) { mutableListOf() } += type
                }
            }
            return spans.entries
                .sortedWith(compareBy<Map.Entry<Pair<Int, Int>, MutableList<KeyType>>> { it.key.first }.thenByDescending { it.key.second - it.key.first })
                .map { Key(text.substring(it.key.first, it.key.second), it.value.toList()) }
        }

        /** Lowercases A-Z only, like the reference. */
        private fun asciiLower(text: String): String = buildString(text.length) {
            for (c in text) append(if (c in 'A'..'Z') c + 32 else c)
        }
    }
}
