// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.media

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Decodes the blurred placeholder of a media event (the blurhash format: base 83 digits holding
 * a DC colour and AC cosine components). The result is a small bitmap that the row scales up.
 */
object Blurhash {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private val cache = LruCache<String, Bitmap>(64)

    fun bitmap(hash: String, width: Int = 32, height: Int = 32): Bitmap? {
        cache.get(hash)?.let { return it }
        val pixels = decode(hash, width, height) ?: return null
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        cache.put(hash, bitmap)
        return bitmap
    }

    /** ARGB pixels, row by row. */
    fun decode(hash: String, width: Int, height: Int): IntArray? {
        if (hash.length < 6) return null
        val sizeFlag = value(hash, 0, 1) ?: return null
        val componentsY = sizeFlag / 9 + 1
        val componentsX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * componentsX * componentsY) return null
        val quantisedMax = value(hash, 1, 2) ?: return null
        val maxValue = (quantisedMax + 1) / 166.0
        val dc = value(hash, 2, 6) ?: return null
        val colors = Array(componentsX * componentsY) { DoubleArray(3) }
        colors[0] = doubleArrayOf(linear(dc shr 16), linear((dc shr 8) and 255), linear(dc and 255))
        for (index in 1 until componentsX * componentsY) {
            val ac = value(hash, 4 + index * 2, 6 + index * 2) ?: return null
            colors[index] = doubleArrayOf(
                signedSquare(ac / (19 * 19) - 9.0, maxValue),
                signedSquare((ac / 19) % 19 - 9.0, maxValue),
                signedSquare(ac % 19 - 9.0, maxValue),
            )
        }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until componentsY) {
                    val cosY = cos(PI * y * j / height)
                    for (i in 0 until componentsX) {
                        val basis = cos(PI * x * i / width) * cosY
                        val color = colors[i + j * componentsX]
                        r += color[0] * basis
                        g += color[1] * basis
                        b += color[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or (srgb(r) shl 16) or (srgb(g) shl 8) or srgb(b)
            }
        }
        return pixels
    }

    private fun value(hash: String, start: Int, end: Int): Int? {
        var result = 0
        for (index in start until end) {
            val digit = DIGITS.indexOf(hash[index])
            if (digit < 0) return null
            result = result * 83 + digit
        }
        return result
    }

    private fun signedSquare(quantised: Double, maxValue: Double): Double {
        val unit = quantised / 9
        return (if (unit < 0) -1 else 1) * unit * unit * maxValue
    }

    private fun linear(value: Int): Double {
        val v = value / 255.0
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun srgb(value: Double): Int {
        val v = value.coerceIn(0.0, 1.0)
        val encoded = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (encoded * 255).roundToInt().coerceIn(0, 255)
    }
}
