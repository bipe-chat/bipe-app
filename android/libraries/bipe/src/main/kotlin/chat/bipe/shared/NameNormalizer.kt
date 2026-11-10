// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.core.TextFold
import chat.bipe.core.normalizeText
import chat.bipe.shared.generated.BipeRegistries
import chat.bipe.shared.generated.ReservedNameCategory

/**
 * `normalize()` of bipe-shared/tools/lib/registries.mjs, run in bipe-core (`normalizeText`, ADR 0267).
 * Must match bipe-shared/test-vectors/normalization.json.
 */
object NameNormalizer {
    /**
     * NFKD, strip combining marks, lowercase, fold homoglyphs, collapse whitespace, trim.
     * `folding = false` skips the homoglyph map (scam phrase matching and handle suggestions do this).
     */
    fun normalize(input: String, folding: Boolean = true): String = normalizeText(input, if (folding) TextFold.NAMES else TextFold.PLAIN)

    /** Category of a reserved display name, or `null` when the name is free to use. */
    fun reservedCategory(displayName: String): ReservedNameCategory? =
        BipeRegistries.reservedNames[normalize(displayName)]

    /** JS `String.prototype.trim()`: strips the same whitespace set as JS, not Kotlin's [String.trim] set. */
    fun trimJavaScript(input: String): String {
        var start = 0
        var end = input.length
        while (start < end) {
            val codePoint = input.codePointAt(start)
            if (!isJavaScriptWhitespace(codePoint)) break
            start += Character.charCount(codePoint)
        }
        while (end > start) {
            val codePoint = input.codePointBefore(end)
            if (!isJavaScriptWhitespace(codePoint)) break
            end -= Character.charCount(codePoint)
        }
        return input.substring(start, end)
    }

    /**
     * JS `\s` and `String.prototype.trim()`: WhiteSpace plus LineTerminator (ECMA-262).
     * Not the same set as Java `\s` or `Character.isWhitespace` (U+00A0, U+FEFF, U+0085 differ).
     */
    private fun isJavaScriptWhitespace(codePoint: Int): Boolean = when (codePoint) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0xFEFF, 0x2028, 0x2029 -> true
        else -> Character.getType(codePoint).toByte() == Character.SPACE_SEPARATOR
    }
}
