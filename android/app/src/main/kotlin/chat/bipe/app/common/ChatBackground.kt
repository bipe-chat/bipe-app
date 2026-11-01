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
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import chat.bipe.shared.R
import chat.bipe.shared.generated.BipeChatBackground
import chat.bipe.shared.generated.BipeColors
import kotlin.math.max
import kotlin.math.min

/**
 * Spec app-colors, ADR 0213: the chat background behind the timeline. MARCA draws the blobs and the Bipe marks of
 * [BipeChatBackground] on chatBackground; COR is chatBackground alone; LISO is surfaceBase. Put it on the list's
 * own modifier: it draws behind the items and stays still while they scroll. The chat colours are themed, so it
 * follows the palette; in high contrast every chat colour is the flat chatBackground, so MARCA shows only the colour.
 */
fun Modifier.chatBackground(option: BipeChatBackground, colors: BipeColors): Modifier = when (option) {
    BipeChatBackground.LISO -> background(colors.surfaceBase)
    BipeChatBackground.COR -> background(colors.chatBackground)
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
        BipeChatBackground.COR -> R.string.bipe_chats_background_cor
        BipeChatBackground.LISO -> R.string.bipe_chats_background_liso
    }
