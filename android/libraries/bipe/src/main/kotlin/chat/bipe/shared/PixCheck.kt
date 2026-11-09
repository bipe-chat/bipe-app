// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.PixCode
import chat.bipe.core.PixCodeStatus
import chat.bipe.core.PixKeyType
import chat.bipe.core.findPixCode
import chat.bipe.core.pixKeyType
import chat.bipe.shared.generated.BipeRegistries
import java.util.regex.Pattern

/**
 * "Conferir no Bipe" phase 1 (bipe ADR 0091): the Pix code and the Pix keys in text that the user
 * shared from another app. Runs on the device only. Must match bipe-shared/test-vectors/pix-check.json.
 * iOS: `PixCheck` in BipeKit. The BR Code, its CRC and the key type come from bipe-core (`findPixCode`, ADR 0267).
 * The keys in the text and the warning stay here until scam matching moves to the core.
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

        fun check(text: String): PixCheck {
            val found = findPixCode(text)
            val rest = found?.let {
                val points = text.codePoints().toArray()
                val start = it.start.toInt()
                val end = it.end.toInt()
                String(points, 0, start) + " " + String(points, end, points.size - end)
            } ?: text
            return PixCheck(
                code = found?.let { code(it.code) },
                keys = keys(rest),
                warning = ScamDetector.warningKind(ScamDetector.matchIds(rest), rest, skipping = skippedWarnings),
            )
        }

        fun keyType(key: String): KeyType = keyType(pixKeyType(key))

        private fun keyType(type: PixKeyType): KeyType = when (type) {
            PixKeyType.CPF -> KeyType.CPF
            PixKeyType.CNPJ -> KeyType.CNPJ
            PixKeyType.PHONE -> KeyType.PHONE
            PixKeyType.EMAIL -> KeyType.EMAIL
            PixKeyType.RANDOM -> KeyType.RANDOM
            PixKeyType.UNKNOWN -> KeyType.UNKNOWN
        }

        private fun code(code: PixCode): Code = when (code.status) {
            PixCodeStatus.MALFORMED -> Code(Status.MALFORMED)
            else -> Code(
                status = if (code.status == PixCodeStatus.VALID) Status.VALID else Status.BAD_CRC,
                dynamic = code.dynamic,
                singleUse = code.singleUse,
                key = code.key,
                keyType = code.keyType?.let { keyType(it) },
                url = code.url,
                name = code.name,
                city = code.city,
                amount = code.amount,
                txid = code.txid,
                info = code.info,
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
    }
}
