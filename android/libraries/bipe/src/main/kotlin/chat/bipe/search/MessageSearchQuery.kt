// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.search

import java.text.Normalizer

/**
 * Spec chat-search notes (bipe-chat/bipe#1143): the text for SearchService.setQuery, where each typed word also
 * matches the start of a longer word, and a message must have all the words. The SDK gives the text to tantivy's
 * query parser. tantivy refuses `"adore"*` for one word, so each word is a range on the body field. The exact word
 * is an optional clause, so it ranks first. MessageSearchQuery.swift in BipeKit is the same on iOS.
 */
object MessageSearchQuery {
    /**
     * The last character of CJK extension H (U+323AF). It is a letter, so the index tokenizer keeps it in the upper
     * bound, and it sorts after every character people type.
     */
    private const val UPPER_BOUND_END = "𲎯"

    /** tantivy's default tokenizer drops a token of 40 UTF-8 bytes or more. The upper bound adds 4 bytes to the word. */
    private const val LONGEST_RANGE_WORD = 35

    /** `null` when the text has no letter or digit: the index holds only those, so nothing can match. */
    fun make(text: String): String? {
        // Lower case, because the parser reads the upper-case word TO as a keyword.
        val words = words(Normalizer.normalize(text, Normalizer.Form.NFC).lowercase())
        if (words.isEmpty()) return null
        return words.joinToString(" ") { word ->
            if (word.toByteArray().size > LONGEST_RANGE_WORD) {
                "+body:$word"
            } else {
                "+body:[$word TO $word$UPPER_BOUND_END] body:$word"
            }
        }
    }

    /** The same word boundaries as the tokenizer, which keeps Rust's char::is_alphanumeric. */
    private fun words(text: String): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        for (codePoint in text.codePoints().toArray()) {
            if (isWordCharacter(codePoint)) {
                word.appendCodePoint(codePoint)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    private fun isWordCharacter(codePoint: Int): Boolean = Character.isAlphabetic(codePoint) ||
        when (Character.getType(codePoint).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
}
