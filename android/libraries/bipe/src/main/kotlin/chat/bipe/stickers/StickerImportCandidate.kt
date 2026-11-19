// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

// Moved from Bipe's libraries/bipe stickers/StickerImport.kt (Bipe code).

package chat.bipe.stickers

import java.security.MessageDigest

class StickerImportCandidate(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    /** Derived from the file's SHA-256, so the same file imported twice gets the same shortcode. */
    val shortcode: String,
    /** Tags from the emoji a WhatsApp WebP carries (spec sticker-import notes). Empty when it carries none. */
    val tags: List<String> = emptyList(),
)

internal fun stickerShortcode(bytes: ByteArray): String =
    "st_" + MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
