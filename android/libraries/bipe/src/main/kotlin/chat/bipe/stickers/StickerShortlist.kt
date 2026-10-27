// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.stickers

import chat.bipe.shared.generated.BipeRegistries
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// Sticker recents and favourites (spec sticker-picker, ADR 0205, bipe-chat/bipe#838). Global account data
// BipeRegistries.STICKER_RECENTS_EVENT_TYPE and STICKER_FAVOURITES_EVENT_TYPE with content
// {"stickers": [{"pack": packId, "shortcode": shortcode}]}. Mirrors StickerShortlist.swift in BipeKit.

/** One sticker in a list, by pack and shortcode. */
data class StickerRef(val pack: String, val shortcode: String) {
    constructor(sticker: Sticker) : this(sticker.packId, sticker.shortcode)
}

object StickerShortlist {
    /** The list in the account data content, in order, without duplicates. Empty on any other content. */
    fun parse(json: String): List<StickerRef> {
        val content = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return emptyList()
        val items = content["stickers"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val pack = (obj["pack"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            val shortcode = (obj["shortcode"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            StickerRef(pack, shortcode)
        }.distinct()
    }

    /** The account data content for a list. */
    fun content(refs: List<StickerRef>): String = buildJsonObject {
        putJsonArray("stickers") {
            refs.forEach { ref ->
                add(buildJsonObject {
                    put("pack", ref.pack)
                    put("shortcode", ref.shortcode)
                })
            }
        }
    }.toString()

    /** A sent sticker goes first in recents, once, and the list keeps at most STICKER_RECENTS_MAX. */
    fun pushRecent(ref: StickerRef, recents: List<StickerRef>): List<StickerRef> =
        (listOf(ref) + recents.filter { it != ref }).take(BipeRegistries.STICKER_RECENTS_MAX)

    /** Adds the sticker first in favourites, or removes it. At STICKER_FAVOURITES_MAX the oldest favourite goes. */
    fun toggleFavourite(ref: StickerRef, favourites: List<StickerRef>): List<StickerRef> =
        if (ref in favourites) favourites - ref else (listOf(ref) + favourites).take(BipeRegistries.STICKER_FAVOURITES_MAX)

    /** The stickers of the list that are in a loaded pack, in list order. The others drop out. */
    fun resolve(refs: List<StickerRef>, packs: List<StickerPack>): List<Sticker> {
        val byRef = LinkedHashMap<StickerRef, Sticker>()
        packs.flatMap { it.stickers }.forEach { byRef.putIfAbsent(StickerRef(it), it) }
        return refs.mapNotNull { byRef[it] }
    }
}
