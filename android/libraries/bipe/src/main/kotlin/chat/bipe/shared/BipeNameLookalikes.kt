// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import chat.bipe.shared.generated.BipeRegistries

/** Security rule 8 (bipe#595). Ports of `sealLookalike()` and `contactCollision()` in bipe-shared tools/lib/registries.mjs. */
object BipeNameLookalikes {
    /** The first [BipeRegistries.sealLookalikeEmoji] entry in [name], or `null`. Variation selectors do not count. */
    fun sealLookalike(name: String): String? {
        val plain = withoutVariationSelectors(name)
        return BipeRegistries.sealLookalikeEmoji.firstOrNull { plain.contains(withoutVariationSelectors(it)) }
    }

    /** The contact name that [name] copies after normalising with the homoglyph map, or `null`. An empty name never collides. */
    fun contactCollision(name: String, contactNames: Collection<String>): String? {
        val normalized = NameNormalizer.normalize(name)
        if (normalized.isEmpty()) return null
        return contactNames.firstOrNull { NameNormalizer.normalize(it) == normalized }
    }

    private fun withoutVariationSelectors(text: String): String = text.replace("︎", "").replace("️", "")
}
