// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.serialization.json.*

/** All four shared backgrounds stay still behind scrolling. The fruit tile is rasterized once per palette/density. */
fun Modifier.chatBackdrop(catalog: DesktopCatalog, colors: DesktopColors,
                          background: String = catalog.appearancePolicy.defaultBackground): Modifier = clipToBounds().drawWithCache {
    val geometry = catalog.backgroundGeometry
    val mark = PathParser().parsePathString(catalog.markGeometry.getValue("path").jsonPrimitive.content).toPath()
    val viewBox = catalog.markGeometry.getValue("viewBox").jsonPrimitive.float
    val fruit = if (background == "frutas") {
        val data = catalog.fruitGeometry
        val tile = data.getValue("tile").jsonPrimitive.float
        val side = (tile * data.getValue("scale").jsonPrimitive.float * density).roundToInt().coerceAtLeast(1)
        val bitmap = ImageBitmap(side, side)
        val glyphs = data.getValue("glyphs").jsonArray.map { value -> value.jsonObject.let { glyph ->
            FruitPaths(glyph.getValue("bodies").jsonArray.paths(), glyph.getValue("lines").jsonArray.paths(), glyph.getValue("dots").jsonArray.paths())
        } }
        CanvasDrawScope().draw(Density(density, fontScale), layoutDirection, Canvas(bitmap), Size(side.toFloat(), side.toFloat())) {
            scale(side / tile, side / tile, pivot = Offset.Zero) { clipRect(0f, 0f, tile, tile) {
                for (placement in data.getValue("placements").jsonArray) {
                    drawFruit(placement.jsonObject, glyphs, data.getValue("stroke").jsonPrimitive.float, colors)
                }
            } }
        }
        bitmap
    } else null
    onDrawBehind {
        if (background == "liso") { drawRect(colors["surface.base"]); return@onDrawBehind }
        drawRect(colors["chat.background"])
        if (background == "cor") return@onDrawBehind
        if (fruit != null) {
            var y = 0
            while (y < size.height) {
                var x = 0
                while (x < size.width) { drawImage(fruit, IntOffset(x, y)); x += fruit.width }
                y += fruit.height
            }
            return@onDrawBehind
        }
        val longSide = maxOf(size.width, size.height)
        val shortSide = minOf(size.width, size.height)
        for (value in geometry.getValue("blobs").jsonArray) {
            val blob = value.jsonObject
            val center = Offset(blob.getValue("x").jsonPrimitive.float * size.width, blob.getValue("y").jsonPrimitive.float * size.height)
            val radius = blob.getValue("radius").jsonPrimitive.float * longSide
            val tone = colors[blob.getValue("tone").jsonPrimitive.content]
            val opacity = geometry.getValue("blobOpacity").jsonPrimitive.float
            drawCircle(Brush.radialGradient(listOf(tone.copy(alpha = opacity), tone.copy(alpha = 0f)), center, radius), radius, center)
        }
        for (value in geometry.getValue("marks").jsonArray) {
            val placement = value.jsonObject
            val side = placement.getValue("side").jsonPrimitive.float * shortSide
            val tone = colors[placement.getValue("tone").jsonPrimitive.content]
            translate(placement.getValue("x").jsonPrimitive.float * size.width, placement.getValue("y").jsonPrimitive.float * size.height) {
                scale(side / viewBox, side / viewBox, pivot = Offset.Zero) {
                    drawPath(mark, tone, alpha = placement.getValue("opacity").jsonPrimitive.float)
                }
            }
        }
    }
}

private data class FruitPaths(val bodies: List<Path>, val lines: List<Path>, val dots: List<Path>)
private fun JsonArray.paths() = map { PathParser().parsePathString(it.jsonPrimitive.content).toPath() }
private fun DrawScope.drawFruit(placement: JsonObject, glyphs: List<FruitPaths>, stroke: Float, colors: DesktopColors) {
    val glyph = glyphs[placement.getValue("glyph").jsonPrimitive.int]
    val factor = placement.getValue("size").jsonPrimitive.float / 48f
    val outline = Stroke(stroke / factor, cap = StrokeCap.Round, join = StrokeJoin.Round)
    translate(placement.getValue("x").jsonPrimitive.float, placement.getValue("y").jsonPrimitive.float) {
        rotate(placement.getValue("rotation").jsonPrimitive.float, pivot = Offset.Zero) {
            scale(if (placement.getValue("mirror").jsonPrimitive.boolean) -factor else factor, factor, pivot = Offset.Zero) {
                translate(-24f, -24f) {
                    glyph.bodies.forEach { drawPath(it, colors["chat.fruitFill"]); drawPath(it, colors["chat.fruit"], style = outline) }
                    glyph.lines.forEach { drawPath(it, colors["chat.fruit"], style = outline) }
                    glyph.dots.forEach { drawPath(it, colors["chat.fruit"]) }
                }
            }
        }
    }
}
