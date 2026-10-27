// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.stickers

import chat.bipe.shared.generated.BipeRegistries
import java.text.Normalizer

// Sticker search on the phone (ADR 0205 rule 3, spec sticker-picker, bipe-chat/bipe#839). The rules are the
// reference rules of bipe-shared tools/lib/registries.mjs; test-vectors/sticker-search.json checks them. This file
// has no network code: search reads only the packs that are already loaded. Mirrors StickerSearch.swift in BipeKit.
object StickerSearch {
    private val marks = Regex("\\p{M}")
    private val notLetterOrDigit = Regex("[^\\p{L}\\p{N}]+")
    private val repeatedLetter = Regex("(\\p{L})\\1+")
    private val spaces = Regex(" +")

    /**
     * NFKD, no marks, lower case, every character that is not a letter or a digit is a space, every run of one
     * repeated letter is one letter (kkkk is k), spaces collapsed and trimmed.
     */
    fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .replace(marks, "")
        .lowercase()
        .replace(notLetterOrDigit, " ")
        .replace(repeatedLetter, "$1")
        .replace(spaces, " ")
        .trim(' ')

    /** An emoji query without variation selectors (U+FE0F) and skin tones (U+1F3FB to U+1F3FF), trimmed. */
    fun emojiKey(text: String): String = buildString {
        text.codePoints().forEach { cp -> if (cp != 0xFE0F && cp !in 0x1F3FB..0x1F3FF) appendCodePoint(cp) }
    }.trim()

    /**
     * The tag ids a query asks for, in registry order. An emoji maps through STICKER_EMOJI. A word maps through
     * STICKER_WORDS and the tag ids (bom-dia is "bom dia"): equal, or a prefix from STICKER_PREFIX_MIN_LENGTH characters.
     */
    fun queryTags(query: String): List<String> {
        val found = mutableSetOf<String>()
        val emoji = BipeRegistries.STICKER_EMOJI[emojiKey(query)]
        if (emoji != null) {
            found += emoji
        } else {
            val q = fold(query)
            if (q.isNotEmpty()) {
                val hits = { word: String -> word == q || (q.length >= BipeRegistries.STICKER_PREFIX_MIN_LENGTH && word.startsWith(q)) }
                BipeRegistries.STICKER_WORDS.forEach { (word, tags) -> if (hits(word)) found += tags }
                BipeRegistries.STICKER_TAGS.forEach { tag -> if (hits(fold(tag))) found += tag }
            }
        }
        return BipeRegistries.STICKER_TAGS.filter { it in found }
    }

    /** A shared tag, or the folded query at the start of a word of the folded body or pack name. */
    fun matches(query: String, body: String, tags: List<String>, pack: String?): Boolean {
        val wanted = queryTags(query)
        if (tags.any { it in wanted }) return true
        val q = fold(query)
        if (q.isEmpty()) return false
        return listOfNotNull(body, pack).any { " ${fold(it)}".contains(" $q") }
    }

    /** State results of spec sticker-picker: the matching stickers, first lists first, without duplicates. */
    fun results(query: String, lists: List<List<Sticker>>, packTitle: (String) -> String?): List<Sticker> =
        lists.flatten().distinctBy { it.id }.filter { matches(query, it.body, it.tags, packTitle(it.packId)) }
}
