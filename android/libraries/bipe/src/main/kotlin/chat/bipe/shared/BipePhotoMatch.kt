// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.shared

import android.graphics.Bitmap
import chat.bipe.shared.generated.BipeRegistries
import kotlin.math.roundToInt

/**
 * ADR 0049 photo match (bipe#597): the dHash of an avatar, kept on the phone only.
 * Ports of `photoHash`, `photoDistance` and `photoMatches` in bipe-shared tools/lib/registries.mjs. Same rules as iOS.
 */
object BipePhotoMatch {
    /** The dHash of a grayscale grid (PHOTO_MATCH_HEIGHT rows of PHOTO_MATCH_WIDTH values 0..255), 16 hex digits; `null` for a wrong size. */
    fun hash(gray: List<List<Int>>): String? {
        val width = BipeRegistries.PHOTO_MATCH_WIDTH
        if (gray.size != BipeRegistries.PHOTO_MATCH_HEIGHT || gray.any { it.size != width }) return null
        var value = 0UL
        for (row in gray) for (x in 0 until width - 1) value = (value shl 1) or (if (row[x] < row[x + 1]) 1UL else 0UL)
        return value.toString(16).padStart(16, '0')
    }

    /** Hamming distance of two hashes; [Int.MAX_VALUE] when one is not a hash. */
    fun distance(a: String, b: String): Int {
        val x = a.toULongOrNull(16) ?: return Int.MAX_VALUE
        val y = b.toULongOrNull(16) ?: return Int.MAX_VALUE
        return (x xor y).countOneBits()
    }

    fun matches(a: String, b: String): Boolean = distance(a, b) <= BipeRegistries.PHOTO_MATCH_MAX_DISTANCE

    /** The grid of an avatar: filtered scaling into width x height pixels, then luma 0.299 R + 0.587 G + 0.114 B. */
    fun grid(bitmap: Bitmap): List<List<Int>> {
        val width = BipeRegistries.PHOTO_MATCH_WIDTH
        val height = BipeRegistries.PHOTO_MATCH_HEIGHT
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return (0 until height).map { y ->
            (0 until width).map { x ->
                val c = small.getPixel(x, y)
                (0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).roundToInt().coerceIn(0, 255)
            }
        }.also { if (small !== bitmap) small.recycle() }
    }
}
