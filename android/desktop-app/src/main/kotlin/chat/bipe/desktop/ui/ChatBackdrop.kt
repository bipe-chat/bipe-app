// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.desktop.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.serialization.json.*

/** Default marca backdrop, with the same geometry/tokens as the other clients; it stays still behind scrolling. */
fun Modifier.chatBackdrop(catalog: DesktopCatalog, colors: DesktopColors): Modifier = clipToBounds().drawWithCache {
    val geometry = catalog.backgroundGeometry
    val mark = PathParser().parsePathString(catalog.markGeometry.getValue("path").jsonPrimitive.content).toPath()
    val viewBox = catalog.markGeometry.getValue("viewBox").jsonPrimitive.float
    onDrawBehind {
        drawRect(colors["chat.background"])
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
