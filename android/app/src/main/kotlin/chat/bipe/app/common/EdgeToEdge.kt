// SPDX-License-Identifier: AGPL-3.0-only
// Additional permission: APP-STORE-PERMISSION.md (AGPL section 7)
// Copyright 2026 Bipe contributors

package chat.bipe.app.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Widens the content by [by] on each side, past the side padding of its parent. A group of list items in a
 * padded screen column uses it to span the screen like a standard Material 3 list (bipe-chat/bipe#612).
 */
fun Modifier.edgeToEdge(by: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val side = by.roundToPx()
    val wide = constraints.copy(
        minWidth = if (constraints.minWidth > 0) constraints.minWidth + side * 2 else 0,
        maxWidth = constraints.maxWidth + side * 2,
    )
    val placeable = measurable.measure(wide)
    val width = (placeable.width - side * 2).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) { placeable.place(-side, 0) }
}
