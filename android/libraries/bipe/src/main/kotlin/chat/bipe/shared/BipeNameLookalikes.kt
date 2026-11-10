// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared


/** Security rule 8 (bipe#595). Ports of `sealLookalike()` and `contactCollision()` in bipe-shared tools/lib/registries.mjs. */
object BipeNameLookalikes {
    /** The first sealLookalikeEmoji entry (registries/profiles.json) in [name], or `null` (bipe-core `sealLookalike`). */
    fun sealLookalike(name: String): String? = chat.bipe.core.sealLookalike(name)

    /** The contact name that [name] copies after normalising with the homoglyph map, or `null` (bipe-core `contactCollision`). */
    fun contactCollision(name: String, contactNames: Collection<String>): String? = chat.bipe.core.contactCollision(name, contactNames.toList())
}
