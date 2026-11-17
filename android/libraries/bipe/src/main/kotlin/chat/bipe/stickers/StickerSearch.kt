// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.stickers

import chat.bipe.core.stickerEmojiKey
import chat.bipe.core.stickerMatches
import chat.bipe.core.stickerQueryTags
import chat.bipe.core.stickerSearchFold

// Sticker search on the phone (ADR 0205 rule 3, spec sticker-picker, bipe-chat/bipe#839). The rules are the
// reference rules of bipe-shared tools/lib/registries.mjs; test-vectors/sticker-search.json checks them. This file
// has no network code: search reads only the packs that are already loaded. Mirrors StickerSearch.swift in BipeKit.
object StickerSearch {
    /** bipe-core `stickerSearchFold`: NFKD, no marks, lower case, letters and digits only, repeated letters once, trimmed. */
    fun fold(text: String): String = stickerSearchFold(text)

    /** bipe-core `stickerEmojiKey`: without variation selectors and skin tones, trimmed. */
    fun emojiKey(text: String): String = stickerEmojiKey(text)

    /** bipe-core `stickerQueryTags`: the tag ids a query asks for, in registry order. */
    fun queryTags(query: String): List<String> = stickerQueryTags(query)

    /** bipe-core `stickerMatches`: a shared tag, or the folded query at the start of a word of the body or pack name. */
    fun matches(query: String, body: String, tags: List<String>, pack: String?): Boolean = stickerMatches(query, body, tags, pack)

    /** State results of spec sticker-picker: the matching stickers, first lists first, without duplicates. */
    fun results(query: String, lists: List<List<Sticker>>, packTitle: (String) -> String?): List<Sticker> =
        lists.flatten().distinctBy { it.id }.filter { matches(query, it.body, it.tags, packTitle(it.packId)) }
}
