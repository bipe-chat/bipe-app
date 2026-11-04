// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeChatBackground
import chat.bipe.shared.generated.BipeChatFruits
import chat.bipe.shared.generated.BipeColors
import kotlin.math.max
import kotlin.math.min

/**
 * Spec app-colors, ADR 0213 and 0215: the chat background behind the timeline. MARCA draws the blobs and the Bipe
 * marks of [BipeChatBackground] on chatBackground; FRUTAS repeats the [BipeChatFruits] tile; COR is chatBackground
 * alone; LISO is surfaceBase. Put it on the list's
 * own modifier: it draws behind the items and stays still while they scroll. The chat colours are themed, so it
 * follows the palette; in high contrast every chat colour is the flat chatBackground, so MARCA shows only the colour.
 */
fun Modifier.chatBackground(option: BipeChatBackground, colors: BipeColors): Modifier = when (option) {
    BipeChatBackground.LISO -> background(colors.surfaceBase)
    BipeChatBackground.COR -> background(colors.chatBackground)
    BipeChatBackground.FRUTAS -> clipToBounds().drawBehind {
        drawRect(colors.chatBackground)
        val unit = BipeChatFruits.SCALE * density
        val side = BipeChatFruits.TILE * unit
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                clipRect(x, y, x + side, y + side) {
                    translate(x, y) {
                        scale(unit, unit, pivot = Offset.Zero) {
                            for (placement in BipeChatFruits.PLACEMENTS) drawFruit(placement, colors)
                        }
                    }
                }
                x += side
            }
            y += side
        }
    }
    BipeChatBackground.MARCA -> clipToBounds().drawBehind {
        drawRect(colors.chatBackground)
        val long = max(size.width, size.height)
        val short = min(size.width, size.height)
        for (blob in BipeChatBackground.BLOBS) {
            val colour = blob.tone.colour(colors)
            val centre = Offset(blob.x * size.width, blob.y * size.height)
            val radius = blob.radius * long
            val brush = Brush.radialGradient(
                listOf(colour.copy(alpha = BipeChatBackground.BLOB_OPACITY), colour.copy(alpha = 0f)),
                center = centre,
                radius = radius,
            )
            drawCircle(brush, radius, centre)
        }
        // In drawing order: the big mark is last, on top.
        for (mark in BipeChatBackground.MARKS) {
            val side = mark.side * short
            val outline = BipeMarkShape.createOutline(Size(side, side), layoutDirection, this)
            translate(mark.x * size.width, mark.y * size.height) {
                drawOutline(outline, mark.tone.colour(colors), alpha = mark.opacity)
            }
        }
    }
}

/** The option name, bipe_chats_background_<option>. */
val BipeChatBackground.title: Int
    get() = when (this) {
        BipeChatBackground.MARCA -> R.string.bipe_chats_background_marca
        BipeChatBackground.FRUTAS -> R.string.bipe_chats_background_frutas
        BipeChatBackground.COR -> R.string.bipe_chats_background_cor
        BipeChatBackground.LISO -> R.string.bipe_chats_background_liso
    }

/**
 * ADR 0215: one glyph of the Salada de frutas tile. Bodies are filled with chatFruitFill and outlined, lines are
 * outlined, dots are filled, all in chatFruit. The outline has the same width on every glyph, whatever its size.
 */
private fun DrawScope.drawFruit(placement: BipeChatFruits.Placement, colors: BipeColors) {
    val s = placement.size / 48f
    val glyph = FRUIT_PATHS[placement.glyph]
    val outline = Stroke(width = BipeChatFruits.STROKE / s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    translate(placement.x, placement.y) {
        rotate(placement.rotation, pivot = Offset.Zero) {
            scale(if (placement.mirror) -s else s, s, pivot = Offset.Zero) {
                translate(-24f, -24f) {
                    for (body in glyph.bodies) {
                        drawPath(body, colors.chatFruitFill)
                        drawPath(body, colors.chatFruit, style = outline)
                    }
                    for (line in glyph.lines) drawPath(line, colors.chatFruit, style = outline)
                    for (dot in glyph.dots) drawPath(dot, colors.chatFruit)
                }
            }
        }
    }
}

/** The BipeChatFruits glyphs as paths, built once. */
private class FruitPaths(val bodies: List<Path>, val lines: List<Path>, val dots: List<Path>)

private val FRUIT_PATHS: List<FruitPaths> by lazy {
    BipeChatFruits.GLYPHS.map { FruitPaths(it.bodies.map(::fruitPath), it.lines.map(::fruitPath), it.dots.map(::fruitPath)) }
}

/** A flat command list: 0 x y move, 1 x y line, 2 x1 y1 x2 y2 x y cubic, 3 close. */
private fun fruitPath(values: FloatArray): Path = Path().apply {
    var i = 0
    while (i < values.size) {
        when (values[i]) {
            0f -> { moveTo(values[i + 1], values[i + 2]); i += 3 }
            1f -> { lineTo(values[i + 1], values[i + 2]); i += 3 }
            2f -> { cubicTo(values[i + 1], values[i + 2], values[i + 3], values[i + 4], values[i + 5], values[i + 6]); i += 7 }
            else -> { close(); i += 1 }
        }
    }
}
